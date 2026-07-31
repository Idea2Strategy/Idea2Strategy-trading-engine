# COM-F Order, Execution, and Ledger Contract Fixture Design

## Context

GitHub issue `Idea2Strategy/Idea2Strategy-trading-engine#3` requires a versioned fixture contract that lets the C, B, E, and A areas develop against F-owned order, execution, settlement, and ledger messages before the execution engine exists. The work is limited to `modules/trading-messaging`; it does not change the database, the root canonical contracts, or C-owned market and evaluation code.

## Chosen approach

Publish immutable Java contract records together with canonical JSON examples and contract tests. Java consumers receive compile-time types, while JSON examples preserve the actual cross-process wire shape for non-Java consumers and future compatibility checks.

The first version lives under `com.idea2strategy.trading.messaging.contract.v1`. Later incompatible shapes must use a new versioned package and fixture directory rather than rewriting v1 semantics.

## Module structure

`modules/trading-messaging` will enable Gradle's `java-test-fixtures` support and contain:

- main contract records and enums under `src/main/java/.../contract/v1`;
- reusable builders and fixture loading support under `src/testFixtures/java/.../fixture/v1`;
- canonical JSON examples under `src/testFixtures/resources/contracts/trading/v1`;
- contract and delivery-scenario tests under `src/test/java/.../contract/v1`.

The module will use the repository's Spring Boot dependency platform for JSON serialization dependencies so versions remain aligned with the existing Java 21 and Spring Boot 4.1.0 baseline.

## Contract model

Every message uses a common envelope containing:

- `schemaVersion` and `eventType` for compatibility routing;
- `eventId` for delivery deduplication;
- `occurredAt` in UTC;
- `producer`, `correlationId`, and `causationId` for tracing;
- `idempotencyKey` for command/batch retry safety;
- `aggregateId` and monotonically increasing `aggregateVersion` for ordering.

The v1 payload families are:

1. `com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch` and `EvaluationResult`: C's producer-owned inbound result, consumed together by F without an independently invented wire type or synthetic bot identity. The candidate shape is `OrderCandidate(candidateId, instrumentId, OrderSide, BigDecimal quantity, BigDecimal limitPrice, List<String> reasonCodes)`.
2. `OrderIntentBatchV1`: F's normalized result after candidate validation. The canonical intent is derived from both upstream resources; accepted, reduced, and rejected examples remain in a separately named decision-scenario resource.
3. `OrderEventV1`: accepted, partially filled, filled, cancelled, expired, and rejected order lifecycle events.
4. `SettlementEventV1`: settlement requested, completed, and failed events with retry-safe settlement identity.
5. `LedgerTransactionV1`: a transaction and its entries, with account, direction, currency, amount, and source event identity.

Order fixtures cover market, limit, stop, stop-limit, and trailing-stop types; DAY, GTC, and GTD time-in-force values; whole-share, fractional-share, and notional quantity modes; and a versioned cost policy carrying the 0.2% fee and 0.05% slippage examples required by the F checklist.

Executable parameters are exact: MARKET carries no price or trail fields; LIMIT requires only a positive `limitPrice`; STOP requires only a positive `stopPrice`; STOP_LIMIT requires both positive prices; and TRAILING_STOP requires only a positive `trailPercent` no greater than 1. The canonical validation matrix exercises valid and rejected combinations across every order type, DAY/GTC/GTD, and whole/fractional/notional quantity modes.

Whole-share quantities may be used by every supported order type. Fractional-share and notional quantities are limited to eligible long market orders with DAY time-in-force; new short orders and all limit, stop, stop-limit, and trailing-stop orders require whole shares. GTD orders require an explicit UTC expiry. These rules are represented by valid and rejected examples rather than implicit defaults.

Decimal wire values are encoded as canonical strings. This avoids accidental binary floating-point conversion in JavaScript or Python consumers. Requests, non-rejected approvals, fills, prices, and ledger postings are strictly positive; only a rejected intent may carry approved quantity zero. Currency validation is deliberately syntax-only (`[A-Z]{3}`), not an ISO membership lookup.

## Ledger invariant

Each ledger transaction contains at least two entries. Debit and credit totals must be equal at the contract boundary. Entries retain the source fill event ID so a consumer can rebuild the official ledger and reject a duplicate delivery without inventing a second transaction. The standalone ledger envelope republishes the exact transaction embedded in the partial-fill event: transaction `601`, entries `611`/`612`, and fill source `501`. Its envelope `eventId` identifies the publication delivery, while `causationId` identifies the fill that caused the posting and therefore equals every ledger `sourceEventId`.

The fixture set contains one balanced partial-fill transaction and invalid examples used only inside tests to prove that an unbalanced transaction is rejected.

## Duplicate and out-of-order delivery scenario

A reusable test-fixture projection consumes envelopes by `eventId` and `aggregateVersion`:

- a previously applied `eventId` is ignored;
- a delivery older than the current aggregate version is ignored;
- the next version is applied once;
- a future version with a gap is rejected as out of order instead of being silently applied.

The first red test delivers accepted v1, then the same partial-fill v2 envelope twice and expects one trade and one set of ledger entries. The projection also enforces accepted→partial→filled sequencing, stable `intentId` and `candidateId`, cumulative quantity bounds, and terminal-state immutability. Cancellation and rejection use independent order aggregates rather than impossible branches after a filled order. Settlement projection state retains settlement, bot, and affected-order identities and permits only REQUESTED attempt 1 → FAILED at the same attempt → COMPLETED at the next attempt; COMPLETED is terminal.

This projection is test-fixture support, not the production trading engine. F01 will later implement durable persistence and recovery using the same contract behavior.

## Canonical fixture set

The JSON resources provide coherent examples across related order lifecycles:

1. C's upstream `contracts/v1/order-candidate-batch.json` and `contracts/v1/evaluation-result.json`, loaded as their producer-owned types by a resource-to-resource consumer contract test;
2. one canonical BUY/LIMIT intent retaining the upstream evaluation, bot, candidate, instrument, quantity 2, and limit 210.12;
3. separately named accepted/reduced/rejected decision scenarios;
4. accepted→partial→filled order history linked to that canonical intent;
5. a separate accepted→cancelled history;
6. a separate rejected history;
7. settlement requested→failed attempt→successful retry history;
8. a standalone republication of the partial-fill ledger transaction, preserving transaction and entry identities;
9. duplicate and out-of-order delivery scenario with expected applied IDs and counts;
10. executable valid/rejected order-intent matrix.

Every resource is deserialized to its Java record and serialized back to the same normalized JSON tree. Tests also verify unique fixture IDs, UTC timestamps, policy-version presence, order-type/time-in-force combinations, decimal-mode examples, and balanced ledger totals.

## Failure behavior

- Unknown v1 enum values fail deserialization instead of falling back to hidden defaults.
- Missing required identifiers, policy versions, or UTC timestamps fail validation.
- Invalid order-type, quantity-mode, or time-in-force combinations fail validation.
- GTD expiry must be strictly after its envelope occurrence time.
- Duplicate events are ignored by event identity.
- Stale events are ignored; sequence gaps are reported explicitly.
- Post-terminal lifecycle transitions and overfills are rejected.
- Order lifecycle `intentId` and `candidateId` drift is rejected independently.
- Settlement bot/order-set drift, invalid retry attempts, and post-completion transitions are rejected.
- Unbalanced ledger transactions are rejected before publication or consumption.

## Compatibility and ownership

F owns order, execution, settlement, and ledger shapes. C owns `evaluation.OrderCandidate`, `evaluation.OrderCandidateBatch`, and `evaluation.EvaluationResult`; F consumes those exact types and both upstream JSON resources. A future incompatible boundary requires a clearly versioned adapter and coordinated review, not a parallel unversioned candidate contract. Consumers may depend on the published F v1 types and JSON examples, but must not depend on fixture-builder internals.

Compatible optional-property additions are ignored by the explicitly configured Jackson fixture mapper. Unknown enum values remain strict and raise `JsonMappingException`. Renaming required fields, changing numeric meaning, or reinterpreting an existing enum requires a new contract version and coordinated consumer tests.

## Verification

Completion requires:

- a demonstrated red-to-green duplicate partial-fill test;
- all `trading-messaging` contract tests passing;
- all trading-engine tests passing;
- a successful Gradle build;
- a clean diff limited to the COM-F issue scope.
