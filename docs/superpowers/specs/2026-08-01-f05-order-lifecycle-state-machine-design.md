# F05 Order Lifecycle State Machine Design

## Context

F05 implements the production order definition and lifecycle required by root checklist item F05 and trading-engine issue #40. F04 already gives every accepted candidate a deterministic intent identity and persists one intent batch atomically. COM-F v1 already owns the public order-type, time-in-force, and lifecycle-event vocabulary. This work therefore consumes those meanings without changing the v1 wire contract or the protected root product sources.

The production model must survive retries, concurrent transitions, and process restarts. It must not implement F07 cash or lot reservations, F08 fill-price simulation, F08A fractional eligibility, F09 trade counting, or F10 ledger posting.

## Chosen approach

Use a domain aggregate as the single transition authority and a PostgreSQL repository that atomically persists the current order snapshot and append-only transition history. An application service creates or loads one order per intent and applies version-checked transitions.

This is preferred over full event sourcing because F05 needs durable audit history but does not yet need event replay to build accounting state. It is preferred over database-trigger-owned transitions because the domain rules remain independently testable and are not duplicated in SQL.

## Order definition

`OrderTerms` contains:

- `intentId`, `candidateId`, and `instrumentId` UUIDs;
- side `BUY` or `SELL`;
- a strictly positive order quantity;
- type `MARKET`, `LIMIT`, `STOP`, `STOP_LIMIT`, or `TRAILING_STOP`;
- time in force `DAY`, `GTC`, or `GTD`;
- optional `limitPrice`, `stopPrice`, `trailPercent`, and `expiresAt`.

The executable combinations match COM-F v1:

- `MARKET` has no price or trail fields.
- `LIMIT` has only a strictly positive `limitPrice`.
- `STOP` has only a strictly positive `stopPrice`.
- `STOP_LIMIT` has strictly positive `stopPrice` and `limitPrice`.
- `TRAILING_STOP` has only a strictly positive `trailPercent` no greater than one.
- `GTD` alone requires an explicit UTC `expiresAt`; it must be after acceptance time.
- `DAY` and `GTC` reject an explicit expiry.

F05 accepts only structurally valid terms. Invalid or contradictory terms fail with `IllegalArgumentException` before persistence. Business rejection remains an explicit initial lifecycle outcome with a non-blank reason code.

## Identity and creation idempotency

An order ID is UUID v5 derived from a fixed F05 namespace, a versioned domain tag, and the intent ID. The request fingerprint is lowercase SHA-256 over a fixed version tag and the complete canonical order definition, using fixed-width UUID and integer encodings and normalized decimal strings.

`OrderLifecycleService.createOrLoad` builds either an `ACCEPTED` or `REJECTED` initial aggregate and delegates to `OrderLifecycleStore.createOrLoad`. The database enforces unique `intent_id` and unique `candidate_id`.

Creation uses a deterministic version-one command ID derived from the order ID and a fixed creation tag. Callers do not invent a second idempotency key for the same intent.

- An exact retry returns the persisted aggregate.
- Reusing an intent, candidate, or derived order ID with different terms or initial outcome raises `OrderLifecycleConflictException`.
- A new order and its version-one history row are committed together or not at all.

There is no persisted `PENDING_APPROVAL` state. COM-F v1 requires lifecycle version one to be `ACCEPTED` or `REJECTED`, and the F04 intent is already the durable pre-order identity.

## Lifecycle aggregate

The production status set is `ACCEPTED`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED`, `EXPIRED`, and `REJECTED`.

Every aggregate holds immutable order terms plus:

- monotonically increasing `version`, starting at one;
- cumulative filled quantity, starting at zero;
- current status;
- acceptance or rejection time;
- last transition time;
- terminal reason for `CANCELLED`, `EXPIRED`, and `REJECTED` only.

Allowed transitions are:

| Current | Command | Result |
| --- | --- | --- |
| `ACCEPTED` | apply positive fill smaller than remaining | `PARTIALLY_FILLED` |
| `ACCEPTED` | apply fill equal to order quantity | `FILLED` |
| `ACCEPTED` | cancel with reason | `CANCELLED` |
| `ACCEPTED` | expire when eligible | `EXPIRED` |
| `PARTIALLY_FILLED` | apply positive fill smaller than remaining | `PARTIALLY_FILLED` |
| `PARTIALLY_FILLED` | apply fill equal to remaining | `FILLED` |
| `PARTIALLY_FILLED` | cancel remaining quantity with reason | `CANCELLED` |
| `PARTIALLY_FILLED` | expire remaining quantity when eligible | `EXPIRED` |

`FILLED`, `CANCELLED`, `EXPIRED`, and `REJECTED` are terminal. Rejection is allowed only as the initial outcome. Fill quantities are deltas: they must be positive and may not exceed remaining quantity. Each successful command increments the version exactly once and records the supplied UTC occurrence time, which must not precede the prior transition time.

## Expiration

Expiration is decided from explicit authoritative time inputs; the aggregate never reads a system clock.

- `DAY` requires the caller to supply the official session close instant for the order's trading session. It may expire when `now` is at or after that close.
- `GTD` may expire when `now` is at or after its stored expiry.
- `GTC` never expires automatically.

Calling expire before the deadline, omitting the DAY session close, attempting to expire GTC, or using a timestamp earlier than the last transition fails without changing state. Market-calendar discovery remains outside F05; callers must provide the official session boundary.

## Application boundary and concurrency

The application service exposes creation, fill, cancel, and expire commands. Mutation commands carry `orderId`, `expectedVersion`, occurrence time, and command-specific values. The store loads the row for update and applies the aggregate transition only if the expected version equals the persisted version.

- Repeating a command with the same command ID and fingerprint does not apply the transition again and returns the current persisted aggregate, even if later commands have advanced it beyond the originally recorded resulting version.
- Reusing a command ID with different content raises a conflict.
- A stale expected version raises `OrderLifecycleVersionConflictException`.
- Domain validation failures do not write a snapshot or history row.
- Unexpected database failures retain their infrastructure exception type.

The store checks an existing command receipt before comparing `expectedVersion`, so an exact retry remains successful after later transitions. This command identity makes caller retries idempotent while optimistic versions prevent two distinct commands from silently winning the same state version.

## Persistence

Flyway adds three F05-owned tables without changing root DBML, which remains under its separate hold policy:

1. `trading_order` stores immutable terms, request fingerprint, current status, cumulative fill, current version, timestamps, and terminal reason.
2. `order_lifecycle_transition` stores one immutable row per order version with command ID, from/to status, fill delta, cumulative fill, occurrence time, and reason.
3. `order_lifecycle_command` stores command ID, request fingerprint, order ID, resulting version, and result status for exact retry detection.

Foreign keys and uniqueness enforce one transition per order version and one global command identity. Numeric checks enforce positive order quantities and prices, bounded trail percent, non-negative cumulative fill no greater than order quantity, and positive versions. Repository reads reconstruct the domain aggregate and reject malformed persisted state rather than normalizing it.

Snapshot update, transition append, and command receipt insert share one transaction. A conflict at any step rolls back the entire command.

## Compatibility

The domain enum names and meanings match COM-F v1, but the domain module does not depend on the messaging module. An application adapter maps production aggregate outcomes to existing `OrderLifecycleContractV1` payloads later; F05 does not revise published fixtures or introduce a v2 contract.

The existing simple `Order` record is replaced or adapted only within trading-engine. No C-owned candidate, root `specs/**`, root `contracts/**`, DBML, reservation, execution-price, ledger, settlement, or UI behavior changes in this work.

## Failure behavior

- Null, malformed, non-positive, contradictory, or non-UTC inputs fail with `IllegalArgumentException`.
- Illegal lifecycle transitions fail with `IllegalStateException` and leave persistence unchanged.
- Identity/content or reused-command mismatches fail with `OrderLifecycleConflictException`.
- Stale versions fail with `OrderLifecycleVersionConflictException`.
- Expiration attempted without an eligible deadline fails explicitly.
- Corrupt persisted rows fail explicitly and are never repaired on read.
- Infrastructure failures propagate unchanged and roll back all F05 writes.

## Test strategy

1. Domain parameterized tests cover every valid and invalid type/time-in-force combination and exact deterministic ID/fingerprint vectors.
2. Domain transition tests cover every allowed edge, all forbidden edges, cumulative-fill boundaries, terminal immutability, timestamp ordering, and DAY/GTC/GTD expiration.
3. Application tests prove exact delegation, null handling, stale-version and identity-conflict propagation, and no store call for structurally invalid input.
4. Real PostgreSQL tests cover exact retry, restart recovery, command deduplication, changed-command conflict, competing versions, rollback, constraints, malformed rows, and ordered history.
5. A trading-worker integration test uses the actual auto-configured transaction manager.
6. Completion requires focused tests, the complete trading-engine suite with zero skipped PostgreSQL tests, worker `bootJar`, and a clean scope diff.
