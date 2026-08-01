# F04 Order Intent Batch Idempotency Design

## Goal

Create one durable, atomic candidate-to-intent identity batch for each evaluation. Identical sequential or concurrent retries return the original batch, while reuse of an evaluation, source candidate batch, or candidate identity with different content fails explicitly instead of creating duplicate downstream orders.

## Scope

F04 owns batch identity, candidate-to-intent identity mapping, deterministic request fingerprinting, and transactional create-or-load persistence. It does not decide order type, time in force, lifecycle transitions, fractional eligibility, reservations, execution, ledger entries, settlement, or COM-F publication.

The existing COM-F `IntentBatch` contract remains unchanged. F04 supplies stable batch and intent identities that a later application integration can combine with F02/F03 decisions and F05 order parameters before F90 publishes the full contract.

## Considered Approaches

### A. Transactional identity registry with deterministic IDs (selected)

Build the desired immutable batch deterministically, then persist the header and every candidate-to-intent mapping in one PostgreSQL transaction. A retry loads and compares the stored aggregate. This provides stable identities, detects changed input, survives process restarts, and prevents partial batches.

### B. Deterministic UUIDs without persistence

Stable UUIDs would make an identical retry name the same objects, but would not prove that the candidate set or batch metadata stayed identical and would not atomically fence concurrent downstream processing.

### C. Reuse only the candidate-batch processing claim

The existing F01 lease prevents two active processors from owning one candidate batch at the same moment. It does not preserve the exact intent mapping after a partial failure, compare retried content, or make the mapping independently recoverable.

## Domain Model

`OrderIntentBatchRequest` contains:

- bot ID;
- evaluation ID;
- source candidate batch ID;
- zero or more candidate IDs.

The constructor rejects null identifiers, null candidate elements, and duplicate candidate IDs. It sorts and defensively copies candidate IDs. An empty list is valid: an evaluation with no candidates is still durably recorded so redelivery does not repeat work.

`OrderIntentBatchFactory.create(request)` returns an immutable `OrderIntentBatch` containing:

- deterministic batch ID;
- original bot, evaluation, and source candidate batch IDs;
- a lowercase 64-character SHA-256 request fingerprint;
- one sorted `OrderIntentIdentity(intentId, candidateId)` per candidate.

The batch ID is RFC 4122 version-5 UUID derived from a fixed F04 namespace and the evaluation ID. Each intent ID is version-5 UUID derived from a separate fixed namespace, the evaluation ID, and candidate ID. Fixed-width UUID bytes and explicit domain tags are used as hash input; string concatenation with ambiguous separators is not used.

The request fingerprint covers a version tag, bot ID, evaluation ID, source candidate batch ID, candidate count, and every sorted candidate ID. It is evidence for exact retry equivalence, not a substitute for database uniqueness.

## Application Boundary

`OrderIntentBatchService.createOrLoad(OrderIntentBatchRequest)` creates the desired aggregate through the factory and delegates to `OrderIntentBatchStore.createOrLoad(OrderIntentBatch)`. The store returns the exact persisted aggregate when it matches or throws `OrderIntentBatchConflictException` when an identity is already bound to different content.

The service and port perform no ordering, reservation, execution, or publication work. A caller may proceed toward F05/F07 only from the returned persisted aggregate.

## Persistence Model

Migration `V2026080102__create_order_intent_identity_batch.sql` adds two service-local tables without changing root DBML or protected canonical files.

`trading.order_intent_batch` stores:

- `batch_id` primary key;
- `evaluation_id` unique and non-null;
- `bot_id` non-null;
- `source_candidate_batch_id` unique and non-null;
- `request_fingerprint` fixed 64-character lowercase hexadecimal with a check constraint;
- `created_at` non-null.

`trading.order_intent_identity` stores:

- `intent_id` primary key;
- `batch_id` foreign key to the header with cascade deletion;
- `candidate_id` globally unique and non-null;
- `ordinal` non-negative and unique within the batch.

The adapter inserts or loads through one Spring transaction. It first attempts the deterministic header with `ON CONFLICT (evaluation_id) DO NOTHING`. A successful header insert is followed by every mapping insert. Any mapping or alternate unique-key conflict rolls back the whole transaction. If the evaluation already exists, the adapter loads the complete stored aggregate and compares batch ID, metadata, fingerprint, ordered candidates, and deterministic intent IDs. Exact equality returns the stored aggregate; any difference throws `OrderIntentBatchConflictException`.

PostgreSQL statement-level uniqueness serialization makes concurrent identical calls converge: one transaction inserts, while the other waits for that outcome and then loads the committed aggregate. No caller receives success before all rows exist.

## Failure and Safety Behavior

- Structurally invalid requests fail fast with `IllegalArgumentException`.
- Duplicate candidate IDs inside one request fail before hashing or persistence.
- Reusing an evaluation ID with different bot, source batch, or candidate set is a conflict.
- Reusing a source candidate batch ID or candidate ID in another evaluation is a conflict and rolls back any new header.
- A database failure while inserting mappings leaves neither a header nor a partial mapping set.
- Same logical input in any candidate order yields the same fingerprint, IDs, stored order, and result.
- The adapter never treats a content conflict as a harmless duplicate and never generates replacement IDs.

## Integration Boundary

F04 adds domain identity types and factory, an application service and persistence port, PostgreSQL migration and adapter, and focused tests. `CandidateBatchProcessor` remains unchanged until an integration slice can assemble F02/F03 outputs and F05 parameters coherently. F05 owns order semantics, F07 owns durable funds and lot reservations, and F90 owns COM-F message publication and real worker wiring.

## Verification

The first persistence test starts two threads with the same evaluation and candidate set. Both calls must return equal batches, while PostgreSQL contains one header and exactly one mapping per candidate.

Additional tests cover:

- deterministic version-5 batch and intent IDs;
- candidate input-order invariance and lowercase SHA-256 fingerprint shape;
- empty candidate batches;
- malformed and duplicate candidate IDs;
- identical sequential retry after a new service instance;
- changed bot, source batch, or candidate set for the same evaluation;
- source candidate batch and candidate identity reuse across evaluations;
- rollback of a newly inserted header when a mapping unique constraint fails;
- stable ordered reload of every mapping;
- complete repository tests and trading-worker executable build.
