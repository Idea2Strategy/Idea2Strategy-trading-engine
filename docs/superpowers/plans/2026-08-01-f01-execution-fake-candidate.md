# F01 Execution And Fake Candidate Consumption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consume fake order-candidate batches once, coordinate order/execution/settlement ports, and prove the durable PostgreSQL boundary and independent worker startup.

**Architecture:** `CandidateBatchProcessor` owns orchestration while ports isolate claims, status updates, orders, executions, and settlements. PostgreSQL atomically admits a batch with `ON CONFLICT DO NOTHING`; JPA updates processing state and jOOQ serves read-side inspection.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Spring JDBC, jOOQ, Flyway, PostgreSQL, JUnit 5, Testcontainers.

## Global Constraints

- Do not modify COM-C evaluation or COM-F contract types.
- Do not modify root `specs/**`, `contracts/**`, DBML, or collaboration policy.
- Use TDD: observe each new behavior fail before adding its production implementation.
- Keep the fake source opt-in through `trading.fake-candidate.enabled`.

---

### Task 1: Batch orchestration and explicit ports

**Files:**
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/Order.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/execution/Execution.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/settlement/Settlement.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/candidate/CandidateBatchProcessor.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/candidate/CandidateBatchProcessingResult.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/port/*.java`
- Test: `modules/trading-application/src/test/java/com/idea2strategy/trading/application/candidate/CandidateBatchProcessorTest.java`
- Modify: `modules/trading-application/build.gradle.kts`

**Interfaces:**
- Consumes: `OrderCandidateBatch` from `trading-messaging`.
- Produces: `CandidateBatchClaimPort.claim(OrderCandidateBatch)`, `CandidateBatchStatusPort.complete/fail`, `OrderPort.place`, `ExecutionPort.execute`, `SettlementPort.settle`, and `CandidateBatchProcessor.process`.

- [ ] Write the failing duplicate-delivery test using in-memory recording ports; assert two calls return `PROCESSED` then `DUPLICATE`, one claim record exists, and each downstream port runs once.
- [ ] Run `bash gradlew :modules:trading-application:test --tests '*CandidateBatchProcessorTest.deliveringSameBatchTwiceProcessesItOnce'` and confirm compilation fails because the processor and ports do not exist.
- [ ] Add the minimal domain records, ports, result enum, and processor needed to pass the duplicate test.
- [ ] Run the focused test and confirm it passes.
- [ ] Add a failing downstream-error test asserting the failed status is recorded and the exception propagates.
- [ ] Add bounded failure recording in the processor, then run the application module tests.
- [ ] Commit as `feat: add idempotent candidate batch orchestration`.

### Task 2: PostgreSQL command and jOOQ query boundary

**Files:**
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/candidate/CandidateBatchProcessingEntity.java`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/candidate/CandidateBatchProcessingRepository.java`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/candidate/PostgresCandidateBatchClaimAdapter.java`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/candidate/JpaCandidateBatchStatusAdapter.java`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/candidate/JooqCandidateBatchQuery.java`
- Create: `modules/trading-persistence/src/main/resources/db/migration/V2026080101__create_candidate_batch_processing.sql`
- Test: `modules/trading-persistence/src/test/java/com/idea2strategy/trading/persistence/candidate/CandidateBatchPersistenceTest.java`
- Modify: `modules/trading-persistence/build.gradle.kts`

**Interfaces:**
- Consumes: application claim/status ports and `OrderCandidateBatch`.
- Produces: atomic `claim`, JPA `complete/fail`, and jOOQ `findByBatchId`/`count` operations.

- [ ] Write a Testcontainers test that migrates PostgreSQL, claims the same batch twice, and expects `true`, `false`, and a jOOQ count of one.
- [ ] Run the focused persistence test and confirm it fails before adapters and migration exist.
- [ ] Add dependencies, the Flyway migration, JPA entity/repository, atomic JDBC claim adapter, and jOOQ query adapter.
- [ ] Run the focused persistence test and confirm it passes.
- [ ] Add a concurrent-claim test using two threads and assert exactly one successful claim.
- [ ] Run all persistence tests and commit as `feat: persist candidate batch claims atomically`.

### Task 3: Fake source, worker wiring, and independent startup

**Files:**
- Create: `apps/trading-worker/src/main/java/com/idea2strategy/trading/worker/candidate/FakeCandidateBatchConfiguration.java`
- Create: `apps/trading-worker/src/main/java/com/idea2strategy/trading/worker/candidate/FakeOrderExecutionConfiguration.java`
- Create: `apps/trading-worker/src/main/java/com/idea2strategy/trading/worker/candidate/CandidateBatchPersistenceConfiguration.java`
- Modify: `apps/trading-worker/src/main/resources/application.yaml`
- Modify: `apps/trading-worker/build.gradle.kts`
- Test: `apps/trading-worker/src/test/java/com/idea2strategy/trading/worker/TradingWorkerApplicationTest.java`

**Interfaces:**
- Consumes: application processor and persistence adapters.
- Produces: opt-in fake batch delivery and deterministic fake order/execution/settlement adapters.

- [ ] Replace the empty context test with a PostgreSQL Testcontainers startup test that enables Flyway and leaves the fake source disabled.
- [ ] Run the focused worker test and confirm it fails because datasource and persistence wiring are absent.
- [ ] Add datasource/Flyway/Testcontainers dependencies, persistence configuration, fake downstream adapters, and opt-in fake source configuration.
- [ ] Run the worker startup test and confirm it passes.
- [ ] Add a fake-source-enabled test and assert the batch processing query reports one completed row.
- [ ] Run all worker tests and commit as `feat: wire standalone fake candidate worker`.

### Task 4: Full verification and handoff

**Files:**
- Modify only files required by failures attributable to F01.

**Interfaces:**
- Consumes: all prior task deliverables.
- Produces: reviewable branch evidence for Issue #5.

- [ ] Run `bash gradlew clean test` and confirm all modules pass.
- [ ] Run `bash gradlew :apps:trading-worker:bootJar` and confirm an executable artifact is produced.
- [ ] Inspect `git diff --check`, `git status`, and the commit range from `origin/develop`.
- [ ] Record the verified commit and prepare the branch for user-approved push and PR creation.
