# F05 Order Lifecycle State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a durable, retry-safe production order definition and lifecycle that supports all F05 order types, DAY/GTC/GTD expiration, valid fills, cancellation, rejection, and terminal-state safety.

**Architecture:** A pure `trading-domain` aggregate owns type validation and state transitions. A small `trading-application` command boundary delegates creation and versioned mutations to a PostgreSQL store, which atomically maintains the current snapshot, immutable transition history, and command receipts. Existing COM-F v1 wire types remain unchanged.

**Tech Stack:** Java 21, Gradle, JUnit 5, AssertJ, Spring JDBC `JdbcClient`, Spring `TransactionTemplate`, Flyway, PostgreSQL 17 Testcontainers, Spring Boot 4.1.0.

## Global Constraints

- Lifecycle version one is exactly `ACCEPTED` or `REJECTED`; do not persist `PENDING_APPROVAL`.
- Supported types are exactly `MARKET`, `LIMIT`, `STOP`, `STOP_LIMIT`, and `TRAILING_STOP`.
- Supported time-in-force values are exactly `DAY`, `GTC`, and `GTD`.
- `MARKET` has no price or trail fields; `LIMIT` has only positive `limitPrice`; `STOP` has only positive `stopPrice`; `STOP_LIMIT` has both positive prices; `TRAILING_STOP` has only `0 < trailPercent <= 1`.
- `GTD` alone requires an explicit UTC expiry strictly after acceptance; `DAY` and `GTC` reject explicit expiry.
- Fill inputs are positive deltas and cumulative fill never exceeds order quantity.
- `FILLED`, `CANCELLED`, `EXPIRED`, and `REJECTED` are terminal.
- Exact command retry is checked before stale-version rejection and never applies a transition twice.
- F07 reservation, F08 price simulation, F08A fractional eligibility, F09 trade counting, F10 ledger posting, COM-F v1 changes, root DBML, and protected root sources are out of scope.
- Unexpected database exceptions propagate unchanged; malformed stored state fails explicitly.

---

### Task 1: Pure order definition, deterministic identity, and lifecycle aggregate

**Files:**
- Preserve: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/Order.java` as the F01 compatibility order used by existing candidate execution ports; F05 adds `OrderLifecycle` alongside it.
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/OrderSide.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/OrderType.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/TimeInForce.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/OrderStatus.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/OrderTerms.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/OrderLifecycle.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/OrderLifecycleFactory.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/order/OrderLifecycleIdentity.java`
- Test: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/order/OrderTermsTest.java`
- Test: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/order/OrderLifecycleTest.java`
- Test: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/order/OrderLifecycleFactoryTest.java`

**Interfaces:**
- Produces `OrderTerms(UUID intentId, UUID candidateId, UUID instrumentId, OrderSide side, BigDecimal quantity, OrderType type, TimeInForce timeInForce, BigDecimal limitPrice, BigDecimal stopPrice, BigDecimal trailPercent, Instant expiresAt)`.
- Produces immutable `OrderLifecycle` accessors for `orderId`, `createCommandId`, `requestFingerprint`, `terms`, `status`, `cumulativeFilledQuantity`, `version`, `createdAt`, `lastTransitionAt`, and `terminalReason`.
- Produces `OrderLifecycleFactory.accepted(OrderTerms, Instant)` and `rejected(OrderTerms, Instant, String)`.
- Produces `OrderLifecycle.applyFill(BigDecimal, Instant)`, `cancel(String, Instant)`, and `expire(Instant, Instant)` returning new aggregates.

- [ ] **Step 1: Write the order-combination tests**

Create a parameterized matrix that constructs all five valid type shapes, all three time-in-force shapes, and rejects each extra/missing field. Include these direct assertions:

```java
assertThatCode(() -> terms(MARKET, DAY, null, null, null, null)).doesNotThrowAnyException();
assertThatThrownBy(() -> terms(MARKET, DAY, TEN, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MARKET");
assertThatThrownBy(() -> terms(STOP_LIMIT, DAY, TEN, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stopPrice");
assertThatThrownBy(() -> terms(TRAILING_STOP, GTC, null, null, new BigDecimal("1.01"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trailPercent");
```

- [ ] **Step 2: Run the terms test and verify RED**

Run: `bash ./gradlew :modules:trading-domain:test --tests '*.order.OrderTermsTest'`

Expected: compilation fails because `OrderTerms` and its enums do not exist.

- [ ] **Step 3: Implement the enums and immutable validated terms**

Use exact enum constants from Global Constraints. Normalize every decimal with `stripTrailingZeros()` after positivity checks; reject a negative scale only by normalized value semantics, not by textual formatting. Require `expiresAt` to have `ZoneOffset.UTC` semantics by accepting `Instant` only.

```java
public record OrderTerms(
        UUID intentId, UUID candidateId, UUID instrumentId,
        OrderSide side, BigDecimal quantity, OrderType type,
        TimeInForce timeInForce, BigDecimal limitPrice,
        BigDecimal stopPrice, BigDecimal trailPercent, Instant expiresAt) {
    public OrderTerms { /* null, positivity, type-shape, and TIF-shape invariants */ }
}
```

- [ ] **Step 4: Run `OrderTermsTest` and verify GREEN**

Run: `bash ./gradlew :modules:trading-domain:test --tests '*.order.OrderTermsTest'`

Expected: all matrix cases pass.

- [ ] **Step 5: Write lifecycle transition tests**

Cover accepted partial fills, repeated partial fills, exact final fill, overfill, zero/negative fill, cancellation/expiration after a partial fill, every terminal-state command, rejection as initial only, and non-monotonic timestamps.

```java
var partial = accepted.applyFill(new BigDecimal("2"), T1);
assertThat(partial.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
assertThat(partial.cumulativeFilledQuantity()).isEqualByComparingTo("2");
assertThat(partial.version()).isEqualTo(2);

var filled = partial.applyFill(new BigDecimal("3"), T2);
assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
assertThatThrownBy(() -> filled.cancel("LATE", T3)).isInstanceOf(IllegalStateException.class);
```

Add expiration cases proving DAY requires a supplied session close, DAY/GTD fail before the deadline and succeed at equality, and GTC always rejects expiration.

- [ ] **Step 6: Run lifecycle tests and verify RED**

Run: `bash ./gradlew :modules:trading-domain:test --tests '*.order.OrderLifecycleTest'`

Expected: compilation fails because `OrderLifecycle` does not exist.

- [ ] **Step 7: Implement immutable state transitions**

Validate reconstructed aggregate state in the public constructor/factory so persistence cannot create impossible combinations. Increment version once, retain immutable terms and identity fields, enforce timestamps, and store reasons only for terminal non-fill outcomes.

```java
public OrderLifecycle applyFill(BigDecimal delta, Instant occurredAt) { /* validated copy */ }
public OrderLifecycle cancel(String reason, Instant occurredAt) { /* validated copy */ }
public OrderLifecycle expire(Instant now, Instant daySessionClose) { /* eligible deadline */ }
```

- [ ] **Step 8: Add deterministic ID and fingerprint golden-vector tests**

Use fixed UUID/time/decimal fixtures and assert exact lowercase SHA-256, UUID v5 version/variant, input sensitivity, scale normalization, and fixed deterministic create-command identity. First run the test without implementation and record the expected compilation failure.

- [ ] **Step 9: Implement `OrderLifecycleIdentity` and factory**

Derive order and creation-command UUIDs with fixed namespaces and versioned UTF-8 tags plus fixed-width UUID bytes. Fingerprint every `OrderTerms` field, the normalized decimals, initial status, created time, and rejection reason using length-prefixed fields.

- [ ] **Step 10: Run all domain tests and commit**

Run: `bash ./gradlew :modules:trading-domain:test --rerun-tasks`

Expected: all domain tests pass.

```bash
git add modules/trading-domain
git commit -m "feat: add F05 order lifecycle domain"
```

---

### Task 2: Application commands and store boundary

**Files:**
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/order/OrderLifecycleConflictException.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/order/OrderLifecycleVersionConflictException.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/order/OrderLifecycleCommand.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/order/FillOrderCommand.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/order/CancelOrderCommand.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/order/ExpireOrderCommand.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/order/OrderLifecycleService.java`
- Create: `modules/trading-application/src/main/java/com/idea2strategy/trading/application/port/OrderLifecycleStore.java`
- Test: `modules/trading-application/src/test/java/com/idea2strategy/trading/application/order/OrderLifecycleServiceTest.java`

**Interfaces:**
- Consumes Task 1 `OrderTerms`, `OrderLifecycle`, and `OrderLifecycleFactory`.
- Produces sealed `OrderLifecycleCommand` with `commandId()`, `orderId()`, `expectedVersion()`, and `occurredAt()`.
- Produces `OrderLifecycleStore.createOrLoad(OrderLifecycle)` and `apply(OrderLifecycleCommand)`.
- Produces service methods `createAccepted`, `createRejected`, `applyFill`, `cancel`, and `expire`.

- [ ] **Step 1: Write service delegation and validation tests**

Use a recording in-memory store and the real factory. Prove null constructor inputs and null requests fail, structurally invalid terms never call the store, store results may not be null, and commands are passed without mutation.

```java
assertThat(service.createAccepted(terms, T0)).isEqualTo(recordingStore.result);
assertThat(recordingStore.created).isEqualTo(factory.accepted(terms, T0));
assertThatThrownBy(() -> service.applyFill(null)).isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: Run application test and verify RED**

Run: `bash ./gradlew :modules:trading-application:test --tests '*.order.OrderLifecycleServiceTest'`

Expected: compilation fails because the application order boundary does not exist.

- [ ] **Step 3: Implement commands, exceptions, port, and service**

Command records validate non-null UUID/time, positive expected version, positive fill delta, non-blank reason, and the DAY session-close argument rules needed by the domain.

```java
public sealed interface OrderLifecycleCommand
        permits FillOrderCommand, CancelOrderCommand, ExpireOrderCommand {
    UUID commandId();
    UUID orderId();
    long expectedVersion();
    Instant occurredAt();
}
```

The service creates desired initial aggregates through the real factory and otherwise delegates commands to the port. It does not catch or translate store exceptions.

- [ ] **Step 4: Run application and domain suites and commit**

Run: `bash ./gradlew :modules:trading-domain:test :modules:trading-application:test --rerun-tasks`

Expected: both suites pass.

```bash
git add modules/trading-application
git commit -m "feat: add F05 order lifecycle boundary"
```

---

### Task 3: Atomic PostgreSQL snapshot, transition history, and command receipts

**Files:**
- Create: `modules/trading-persistence/src/main/resources/db/migration/V2026080103__create_order_lifecycle.sql`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/order/OrderLifecyclePersistenceView.java`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/order/JooqOrderLifecycleQuery.java`
- Create: `modules/trading-persistence/src/main/java/com/idea2strategy/trading/persistence/order/PostgresOrderLifecycleStore.java`
- Test: `modules/trading-persistence/src/test/java/com/idea2strategy/trading/persistence/order/OrderLifecyclePersistenceTest.java`

**Interfaces:**
- Implements Task 2 `OrderLifecycleStore` using Task 1 aggregate transitions.
- Produces read methods `findByOrderId(UUID)` and `findTransitions(UUID)` for verification and later F15 projection work.

- [ ] **Step 1: Write a real-PostgreSQL creation/retry test**

Start `postgres:17-alpine`, run Flyway through the module test setup, create an accepted order, repeat it, reconstruct a fresh store instance, and repeat again. Assert one snapshot, one version-one transition, one command receipt, and exact aggregate equality.

- [ ] **Step 2: Run the focused persistence test and verify RED**

Run: `TESTCONTAINERS_RYUK_DISABLED=true bash ./gradlew :modules:trading-persistence:test --tests '*.order.OrderLifecyclePersistenceTest'`

Expected: compilation or migration lookup fails because F05 persistence does not exist.

- [ ] **Step 3: Add the migration with executable constraints**

Create `trading.trading_order`, `trading.order_lifecycle_transition`, and `trading.order_lifecycle_command`. Use `numeric(38,18)`, `timestamptz`, lowercase 64-hex fingerprint checks, enum-string checks, unique intent/candidate IDs, unique `(order_id, version)`, and global unique command IDs. Add checks for quantity/fill bounds, type field combinations, TIF expiry shape, terminal reason shape, and positive version.

- [ ] **Step 4: Implement strict row reconstruction and query adapter**

`OrderLifecyclePersistenceView.toDomain()` must call the domain reconstruction path and translate no invalid values. `JooqOrderLifecycleQuery` returns ordered transition views by version and empty optionals for absent orders.

- [ ] **Step 5: Implement transactional `createOrLoad`**

Use one default `TransactionTemplate` compatible with the worker's actual `JpaTransactionManager`. Insert the snapshot with untargeted `ON CONFLICT DO NOTHING`; on insert, add deterministic create receipt and version-one history. On zero rows, load by intent ID and require complete aggregate equality. Do not catch broad `DataAccessException`.

- [ ] **Step 6: Write mutation, idempotency, and rollback tests**

Cover each legal transition, each illegal transition, exact command replay after later state advancement, changed-content command reuse, stale distinct commands, competing same-version commands, restart replay, overfill, terminal immutability, DAY/GTC/GTD expiration, rollback after history/receipt conflicts, malformed stored UUID/status/version/fill state, ordered history, and infrastructure failure propagation.

```java
var first = store.apply(fill(commandId, orderId, 1, "2", T1));
var later = store.apply(fill(otherCommandId, orderId, 2, "1", T2));
assertThat(store.apply(fill(commandId, orderId, 1, "2", T1))).isEqualTo(later);
assertThat(query.findTransitions(orderId)).extracting(TransitionView::version).containsExactly(1L, 2L, 3L);
```

- [ ] **Step 7: Implement transactional `apply`**

Within one transaction: load command receipt first; if exact, return current snapshot; if mismatched, conflict. Otherwise lock the order row `FOR UPDATE`, compare expected version, apply the domain command, insert command receipt and transition, and update the snapshot with `where version = :expectedVersion`. Require exactly one row at each write.

- [ ] **Step 8: Run persistence plus lower-layer suites and commit**

Run: `TESTCONTAINERS_RYUK_DISABLED=true bash ./gradlew :modules:trading-domain:test :modules:trading-application:test :modules:trading-persistence:test --rerun-tasks`

Expected: PostgreSQL tests execute with zero skips/failures and all lower suites pass.

```bash
git add modules/trading-persistence
git commit -m "feat: persist F05 order lifecycle atomically"
```

---

### Task 4: Actual worker transaction manager and final boundary coverage

**Files:**
- Modify: `apps/trading-worker/src/test/java/com/idea2strategy/trading/worker/TradingWorkerApplicationTest.java`
- Modify only if component discovery requires it: `apps/trading-worker/src/main/java/com/idea2strategy/trading/worker/TradingWorkerApplication.java`
- Test: existing Task 1–3 test files for any missing boundary cases found by review.

**Interfaces:**
- Consumes `PostgresOrderLifecycleStore`, `JooqOrderLifecycleQuery`, and the auto-configured `PlatformTransactionManager`.
- Proves F05 works with the actual worker `JpaTransactionManager`, not a test-only JDBC manager.

- [ ] **Step 1: Write the worker integration test and verify RED**

Autowire the F05 store/query and assert the transaction manager is `JpaTransactionManager`. Inside an outer `TransactionTemplate`, create an accepted order, apply a partial fill, replay the same command, run `select 1`, and assert only versions one and two exist.

Run: `TESTCONTAINERS_RYUK_DISABLED=true bash ./gradlew :apps:trading-worker:test --tests '*.TradingWorkerApplicationTest'`

Expected: test fails before production wiring is available or before the expected F05 operations are present.

- [ ] **Step 2: Add only the minimal worker wiring needed for GREEN**

Prefer Spring component discovery on `@Repository`. If a factory/service bean is required, add a focused `@Configuration` with explicit constructor wiring; do not add messaging publication or scheduled expiration.

- [ ] **Step 3: Run focused worker and direct F05 suites**

Run:

```bash
TESTCONTAINERS_RYUK_DISABLED=true bash ./gradlew \
  :modules:trading-domain:test \
  :modules:trading-application:test \
  :modules:trading-persistence:test \
  :apps:trading-worker:test --rerun-tasks
```

Expected: all F05 tests execute with zero skips/failures.

- [ ] **Step 4: Run full verification**

Run:

```bash
TESTCONTAINERS_RYUK_DISABLED=true bash ./gradlew clean test --rerun-tasks
TESTCONTAINERS_RYUK_DISABLED=true bash ./gradlew :apps:trading-worker:bootJar --rerun-tasks
git diff --check origin/develop...HEAD
git status --short --branch
```

Inspect every `TEST-*.xml` and aggregate `tests`, `skipped`, `failures`, and `errors`; PostgreSQL suites must not be skipped.

- [ ] **Step 5: Commit integration coverage**

```bash
git add apps/trading-worker modules/trading-domain/src/test modules/trading-application/src/test modules/trading-persistence/src/test
git commit -m "test: cover F05 lifecycle boundaries"
```

The branch is ready for whole-branch review only after the full verification evidence is fresh and the diff contains no COM-F v1, root protected, F07, F08, F08A, F09, or F10 behavior.
