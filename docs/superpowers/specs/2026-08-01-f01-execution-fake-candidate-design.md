# F01 Execution And Fake Candidate Consumption Design

## Goal

Consume an `OrderCandidateBatch` in the trading worker, admit each `batchId` at most once, and keep order, execution, settlement, and persistence responsibilities behind explicit ports. The first delivered slice uses a fake candidate source and remains independently startable and testable without the unfinished strategy evaluator.

## Constraints

- Java 21 and Spring Boot 4.1.0 remain the repository baseline.
- The input contract is the COM-C `OrderCandidateBatch`; COM-C market and evaluation code is not modified.
- COM-F order, execution, settlement, and ledger contracts remain unchanged.
- Official order processing stays server-side and users cannot submit direct orders.
- The relational database is the durable idempotency boundary.
- Command writes use JPA where ordinary entity lifecycle is sufficient and atomic PostgreSQL SQL where compare-and-set behavior is required. Query reads use a jOOQ boundary.

## Considered Approaches

### A. Atomic database admission before orchestration (selected)

Insert a processing row keyed by `batch_id` with `ON CONFLICT DO NOTHING`. Only the caller that inserts the row may invoke downstream ports. This survives restarts, makes concurrent duplicate deliveries deterministic, and provides an audit record with a small schema surface.

### B. In-memory batch ID set

This is simple and useful in a unit fake, but duplicate protection disappears after restart and cannot coordinate multiple workers. It is retained only as a test adapter, not as the production boundary.

### C. Broker-level exactly-once processing

This shifts idempotency to delivery infrastructure and couples F01 to unfinished messaging topology. It also cannot by itself prove that database side effects occurred only once, so it is outside this slice.

## Architecture

`CandidateBatchProcessor` is the application use case. A worker input adapter translates the COM-C message into the dependency-neutral domain `CandidateBatch`, avoiding a cycle because `trading-messaging` already depends on `trading-application`. The processor asks `CandidateBatchClaimPort` to claim the batch, returns `DUPLICATE` when the claim fails, and otherwise processes every candidate through `OrderPort`, `ExecutionPort`, and `SettlementPort`. A successful batch is marked completed; an exception is recorded as failed and rethrown so delivery infrastructure can apply its retry policy without silently hiding failure.

The domain module owns small order, execution, and settlement value objects and their statuses. The application module owns orchestration and port interfaces. The persistence module owns the JPA processing entity, the atomic JDBC claim adapter, and a jOOQ query adapter. The trading-worker app wires fake downstream adapters and an optional fake source so the application can start and demonstrate the flow independently.

## Data Flow

1. A fake source supplies one valid `OrderCandidateBatch` when `trading.fake-candidate.enabled=true`.
2. The processor attempts to insert `trading.candidate_batch_processing(batch_id, evaluation_id, created_at, status)`.
3. A zero-row insert means the batch is a duplicate; no order, execution, or settlement port is invoked.
4. For a new batch, candidates are passed in stable list order through the three ports.
5. The processing row is marked `COMPLETED`. On failure it is marked `FAILED` with a bounded reason and the original exception propagates.

## Persistence

The processing table has one row per `batch_id`, a unique primary key, the evaluation ID, source creation time, processing status, timestamps, and an optional failure reason. Flyway creates the `trading` schema and table for standalone tests. The root DBML remains authoritative and is not changed in F01; root integration can reconcile the new physical table separately.

The claim command uses PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`. Completion and failure updates use a Spring Data JPA repository. Read-side inspection is exposed through a jOOQ query adapter, keeping query construction out of the command adapter.

## Failure Behavior

- A duplicate batch returns `DUPLICATE` and produces no downstream effects.
- An empty candidate list is a valid batch and completes after admission.
- A downstream port exception marks the batch failed and propagates.
- A database admission failure propagates; processing never starts without a durable claim.
- Failure text is bounded before persistence so arbitrary exception payloads do not expand the row indefinitely.

## Verification

- Application unit test: delivering the same batch twice produces one processing record and one set of downstream calls.
- Application unit test: downstream failure records `FAILED` and propagates.
- PostgreSQL Testcontainers test: duplicate and concurrent claims insert exactly one row, and the jOOQ query reads it.
- Spring Boot Testcontainers test: Flyway migrates a fresh PostgreSQL instance and the trading-worker context starts independently.
- Full repository test suite verifies COM-C and COM-F contract compatibility remains intact.
