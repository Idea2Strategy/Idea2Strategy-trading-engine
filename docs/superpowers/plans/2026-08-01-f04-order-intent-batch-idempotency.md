# F04 Order Intent Batch Idempotency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist one deterministic candidate-to-intent identity batch per evaluation atomically, returning the original aggregate for exact retries and rejecting conflicting identity reuse.

**Architecture:** A pure domain factory sorts candidate IDs, derives version-5 UUIDs, and hashes the complete request. An application service delegates the desired aggregate to a create-or-load port. A PostgreSQL adapter writes header and mappings in one transaction and compares complete stored content on retry.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit Jupiter, Spring JDBC transactions, PostgreSQL 17, Flyway, Testcontainers, jOOQ

## Global Constraints

- Do not change COM-F messaging contracts, root `specs/**`, root `contracts/**`, root DBML, or existing F01-F03 behavior.
- F04 owns identity batching and idempotency only; F05 owns order semantics, F07 reservations, and F90 publication/wiring.
- Candidate input order must not affect fingerprint, IDs, persistence order, or returned result.
- Same evaluation and exact request returns the original aggregate; changed metadata or candidate set is a conflict.
- Header and every mapping commit together or all roll back.
- Empty candidate lists are valid durable batches.
- Never replace conflicting IDs, silently overwrite content, or treat a conflict as a duplicate success.

---

### Task 1: Deterministic intent identity aggregate

**Files:**
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/intent/OrderIntentBatchRequest.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/intent/OrderIntentIdentity.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/intent/OrderIntentBatch.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/intent/OrderIntentBatchFactory.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/intent/OrderIntentIdentityHashing.java`
- Test: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/intent/OrderIntentBatchFactoryTest.java`

**Interfaces:**
- Consumes: `OrderIntentBatchRequest(UUID botId, UUID evaluationId, UUID sourceCandidateBatchId, List<UUID> candidateIds)`.
- Produces: `OrderIntentBatchFactory.create(request)` returning `OrderIntentBatch(UUID batchId, UUID botId, UUID evaluationId, UUID sourceCandidateBatchId, String requestFingerprint, List<OrderIntentIdentity> intents)`.

- [ ] **Step 1: Write failing deterministic-aggregate tests**

Add tests that create the same request with candidate IDs in forward and reverse order and assert equal complete aggregates, sorted candidate IDs, lowercase `[0-9a-f]{64}` fingerprint, UUID version `5` for batch and intent IDs, and an empty request producing an empty durable aggregate. Add constructor tests for null IDs, null elements, and duplicates.

- [ ] **Step 2: Verify RED**

```bash
bash ./gradlew :modules:trading-domain:test --tests '*OrderIntentBatchFactoryTest'
```

Expected: compilation fails because the intent types do not exist.

- [ ] **Step 3: Implement the immutable records and factory**

Use these exact public signatures:

```java
public record OrderIntentBatchRequest(
        UUID botId, UUID evaluationId, UUID sourceCandidateBatchId, List<UUID> candidateIds) { }

public record OrderIntentIdentity(UUID intentId, UUID candidateId) { }

public record OrderIntentBatch(
        UUID batchId,
        UUID botId,
        UUID evaluationId,
        UUID sourceCandidateBatchId,
        String requestFingerprint,
        List<OrderIntentIdentity> intents) { }

public final class OrderIntentBatchFactory {
    public OrderIntentBatch create(OrderIntentBatchRequest request) { }
}
```

Constructors require non-null values, copy lists, reject duplicates, sort request candidates by UUID natural order, require result mappings sorted by candidate ID with unique candidate and intent IDs, and validate the fingerprint with `[0-9a-f]{64}`.

`OrderIntentIdentityHashing` is package-private. It converts UUIDs to fixed 16-byte big-endian form. Implement RFC 4122 version-5 IDs with SHA-1 over namespace bytes plus explicit UTF-8 domain tag and UUID bytes, then set version bits to 5 and RFC variant bits. Use separate fixed namespace constants for batches and intents. Hash the request with SHA-256 over a version tag, bot/evaluation/source UUID bytes, four-byte candidate count, and sorted candidate UUID bytes.

- [ ] **Step 4: Verify GREEN and domain regression**

```bash
bash ./gradlew :modules:trading-domain:test --tests '*OrderIntentBatchFactoryTest'
bash ./gradlew :modules:trading-domain:test
```

- [ ] **Step 5: Commit**

```bash
git add modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/intent modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/intent
git commit -m "feat: add F04 intent identity aggregate"
```

### Task 2: Application create-or-load boundary

**Files:**
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/intent/OrderIntentBatchConflictException.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/intent/OrderIntentBatchService.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/port/OrderIntentBatchStore.java`
- Test: `modules/trading-application/src/test/java/com/idea2strategy/trading/application/intent/OrderIntentBatchServiceTest.java`

**Interfaces:**
- Consumes: Task 1 request/factory and `OrderIntentBatchStore.createOrLoad(OrderIntentBatch desired)`.
- Produces: `OrderIntentBatchService.createOrLoad(OrderIntentBatchRequest request)` returning the persisted aggregate.

- [ ] **Step 1: Write failing service tests**

Use a recording fake store to prove the service passes the exact factory output once and returns the store's persisted instance. Add a fake that throws `OrderIntentBatchConflictException` and assert the same exception propagates without retry or ID replacement.

- [ ] **Step 2: Verify RED**

```bash
bash ./gradlew :modules:trading-application:test --tests '*OrderIntentBatchServiceTest'
```

Expected: compilation fails because the service and port do not exist.

- [ ] **Step 3: Implement the minimal boundary**

```java
public interface OrderIntentBatchStore {
    OrderIntentBatch createOrLoad(OrderIntentBatch desired);
}

public final class OrderIntentBatchService {
    public OrderIntentBatchService(OrderIntentBatchFactory factory, OrderIntentBatchStore store) { }
    public OrderIntentBatch createOrLoad(OrderIntentBatchRequest request) { }
}

public final class OrderIntentBatchConflictException extends RuntimeException {
    public OrderIntentBatchConflictException(String message) { super(message); }
    public OrderIntentBatchConflictException(String message, Throwable cause) { super(message, cause); }
}
```

Require non-null dependencies/request/result. Call the factory once and the store once. Do not add Spring annotations or orchestration behavior.

- [ ] **Step 4: Verify GREEN and commit**

```bash
bash ./gradlew :modules:trading-application:test --tests '*OrderIntentBatchServiceTest'
bash ./gradlew :modules:trading-application:test
git add modules/trading-application/src/main/java/com/idea2strategy/trading/application/intent modules/trading-application/src/main/java/com/idea2strategy/trading/application/port/OrderIntentBatchStore.java modules/trading-application/src/test/java/com/idea2strategy/trading/application/intent
git commit -m "feat: add F04 create-or-load boundary"
```

### Task 3: Atomic PostgreSQL create-or-load adapter

**Files:**
- Create: `modules/trading-persistence/src/main/resources/db/migration/V2026080102__create_order_intent_identity_batch.sql`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/intent/PostgresOrderIntentBatchStore.java`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/intent/JooqOrderIntentBatchQuery.java`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/intent/OrderIntentBatchPersistenceView.java`
- Test: `modules/trading-persistence/src/test/java/com/idea2strategy/trading/persistence/intent/OrderIntentBatchPersistenceTest.java`

**Interfaces:**
- Consumes: `OrderIntentBatchStore` and the complete Task 1 aggregate.
- Produces: transactional create-or-load plus query counts and complete ordered views for integration assertions.

- [ ] **Step 1: Write the concurrent first failing test**

Start PostgreSQL 17 through the existing Testcontainers pattern, run Flyway, and create a real adapter. Submit one two-candidate aggregate from two threads released by a latch. Assert both results equal the desired aggregate, header count is `1`, mapping count is `2`, and the stored ordered view equals the aggregate.

- [ ] **Step 2: Verify RED**

```bash
bash ./gradlew :modules:trading-persistence:test --tests '*OrderIntentBatchPersistenceTest.concurrentIdenticalRequestsConvergeOnOneCompleteBatch'
```

Expected: compilation fails because the migration, adapter, and query types do not exist.

- [ ] **Step 3: Add the exact schema**

Create `trading.order_intent_batch` and `trading.order_intent_identity` exactly as the design specifies. Add primary, unique, foreign-key, ordinal, and lowercase 64-hex fingerprint constraints. Use `created_at timestamptz not null default current_timestamp` on both tables.

- [ ] **Step 4: Implement transactional create-or-load**

`PostgresOrderIntentBatchStore` is a `@Repository` implementing the port. Its constructor accepts `JdbcClient` and `PlatformTransactionManager` and creates a `TransactionTemplate`. Within one transaction:

1. insert the header using `ON CONFLICT (evaluation_id) DO NOTHING`;
2. if inserted, insert every mapping with its sorted zero-based ordinal and return the desired aggregate;
3. otherwise load by evaluation ID and require complete aggregate equality before returning it.

Translate alternate unique-key or mapping constraint failures to `OrderIntentBatchConflictException` outside the failed transaction so no query occurs in an aborted transaction. If a stored aggregate is absent, incomplete, or differs in any field, throw the same conflict exception.

`JooqOrderIntentBatchQuery` loads header and mappings ordered by ordinal and exposes `countBatches()`, `countMappings()`, and `findByEvaluationId(UUID)` returning an `OrderIntentBatchPersistenceView` whose `toDomain()` reconstructs the aggregate.

- [ ] **Step 5: Verify the concurrent test GREEN**

Run the focused command from Step 2. Expected: one test passes when Docker is available; CI must execute it. If local Docker is unavailable, record the JUnit skip and rely on compilation plus CI without claiming local container execution.

- [ ] **Step 6: Add conflict, rollback, and restart tests**

Add real PostgreSQL tests proving sequential identical retry through a new adapter instance; same evaluation with changed bot, source batch, or candidate set conflicts; source candidate batch and candidate ID reuse across evaluations conflicts; mapping conflict leaves no new header; empty batches persist and reload; and reversed input reloads in stable sorted order.

- [ ] **Step 7: Run persistence and domain/application tests, then commit**

```bash
bash ./gradlew :modules:trading-persistence:test --rerun-tasks
bash ./gradlew :modules:trading-domain:test :modules:trading-application:test --rerun-tasks
git add modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/intent modules/trading-persistence/src/main/resources/db/migration/V2026080102__create_order_intent_identity_batch.sql modules/trading-persistence/src/test/java/com/idea2strategy/trading/persistence/intent
git commit -m "feat: persist F04 intent batches atomically"
```

### Task 4: Integration and boundary verification

**Files:**
- Modify only if a verified defect requires it: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/intent/*.java`
- Modify only if a verified defect requires it: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/intent/*.java`
- Modify only if a verified defect requires it: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/intent/*.java`
- Test: corresponding F04 test packages only.

**Interfaces:**
- Consumes: completed domain, application, and persistence implementation.
- Produces: merge evidence for Issue #32.

- [ ] **Step 1: Audit every design invariant**

Confirm direct tests cover UUID version/variant, fingerprint content/order invariance, immutable copies, duplicate/null rejection, empty batches, exact retry, every conflict dimension, concurrency, full rollback, and stable ordered reload. Add only missing assertions; use RED/GREEN for any production correction.

- [ ] **Step 2: Run fresh verification**

```bash
bash ./gradlew :modules:trading-domain:test :modules:trading-application:test :modules:trading-persistence:test --rerun-tasks
bash ./gradlew clean test --rerun-tasks
bash ./gradlew :apps:trading-worker:bootJar --rerun-tasks
```

- [ ] **Step 3: Audit scope and migration order**

```bash
git diff --check origin/develop...HEAD
git diff --name-status origin/develop...HEAD
git status -sb
```

Confirm only F04 docs, domain/application/persistence implementation, migration, and tests changed. Confirm migration follows `V2026080101` and no protected root, messaging, worker wiring, F05 semantics, or F07 reservation behavior changed.

- [ ] **Step 4: Commit any final verified correction**

If the audit required a correction, commit only affected F04 files:

```bash
git add modules/trading-domain/src modules/trading-application/src modules/trading-persistence/src
git commit -m "test: cover F04 idempotency boundaries"
```
