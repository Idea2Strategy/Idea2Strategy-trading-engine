# COM-F Order, Execution, and Ledger Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish versioned Java and JSON fixtures for F-owned order, execution, settlement, and ledger messages, including deterministic duplicate and out-of-order delivery behavior.

**Architecture:** `modules/trading-messaging` exposes immutable v1 Java records from main source, reusable fixture builders and loaders through Gradle test fixtures, and canonical JSON examples through test-fixture resources. Contract constructors enforce wire invariants; a test-only projection demonstrates the idempotency and sequence rules that F01 must later persist durably.

**Tech Stack:** Java 21, Gradle 8.14.3, Spring Boot dependency BOM 4.1.0, Jackson Databind managed by the Boot BOM, JUnit Jupiter 6, AssertJ.

## Global Constraints

- Modify only `modules/trading-messaging/**` and COM-F documentation in the `trading-engine` repository.
- Do not change the root repository, `specs/**`, `contracts/**`, DBML, Flyway migrations, persistence modules, or C-owned market/evaluation code.
- Keep public wire types in `com.idea2strategy.trading.messaging.contract.v1`; incompatible future shapes require a new versioned package.
- Encode decimal wire values as canonical nonnegative strings and timestamps as UTC `Instant` values.
- Require positive requests, non-rejected approvals, fills, prices, and postings; approved quantity zero is reserved for rejected intents. Currency validation is syntax-only (`[A-Z]{3}`).
- Use the exact example fee rate `0.002` and slippage rate `0.0005`, each accompanied by an explicit policy version.
- Fractional-share and notional orders are allowed only for eligible long MARKET/DAY orders; new shorts and all LIMIT, STOP, STOP_LIMIT, and TRAILING_STOP orders require whole shares.
- GTD requires an explicit UTC expiry; DAY and GTC must not carry one.
- Follow red-green-refactor for every behavior and commit only after the focused and module tests pass.

---

## File map

- Modify `modules/trading-messaging/build.gradle.kts` to enable test fixtures and BOM-managed JSON/test dependencies.
- Create `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/ContractValidationV1.java` for shared constructor validation.
- Create `.../TradingEnvelopeV1.java` for trace, identity, idempotency, and aggregate sequence metadata.
- Create `.../DecimalValueV1.java` and `.../CurrencyAmountV1.java` for stable decimal wire values.
- Consume C's existing `com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch` directly and protect that boundary with a consumer contract test.
- Create `.../OrderExecutionContractV1.java` for intent batches, cost policy, order type, side, quantity mode, and time-in-force.
- Create `.../OrderLifecycleContractV1.java` for accepted, partial-fill, fill, cancel, expire, and reject events.
- Create `.../SettlementContractV1.java` for requested, completed, and failed settlement events.
- Create `.../LedgerContractV1.java` for balanced transaction and entry records.
- Create `modules/trading-messaging/src/testFixtures/java/.../ContractFixturesV1.java` for deterministic fixture objects.
- Create `.../ContractJsonFixtureLoaderV1.java` for typed Jackson reads and normalized JSON comparisons.
- Create `.../FixtureDeliveryProjectionV1.java` for duplicate/stale/gap behavior used only by tests.
- Create JSON resources under `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/`.
- Create focused JUnit tests under `modules/trading-messaging/src/test/java/.../contract/v1/`.

---

### Task 1: Establish the duplicate partial-fill contract boundary

**Files:**
- Modify: `modules/trading-messaging/build.gradle.kts`
- Create: `modules/trading-messaging/src/test/java/com/idea2strategy/trading/messaging/contract/v1/DuplicatePartialFillDeliveryTest.java`
- Create: `modules/trading-messaging/src/test/java/com/idea2strategy/trading/messaging/contract/v1/TradingEnvelopeV1Test.java`
- Create: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/ContractValidationV1.java`
- Create: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/TradingEnvelopeV1.java`
- Create: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/DecimalValueV1.java`
- Create: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/CurrencyAmountV1.java`
- Create: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/LedgerContractV1.java`
- Create: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/OrderLifecycleContractV1.java`
- Create: `modules/trading-messaging/src/testFixtures/java/com/idea2strategy/trading/messaging/fixture/v1/ContractFixturesV1.java`
- Create: `modules/trading-messaging/src/testFixtures/java/com/idea2strategy/trading/messaging/fixture/v1/FixtureDeliveryProjectionV1.java`

**Interfaces:**
- Consumes: Java 21 records and the repository's Spring Boot 4.1.0 dependency platform.
- Produces: `TradingEnvelopeV1<T>`, partial-fill and balanced-ledger payloads, and `FixtureDeliveryProjectionV1.accept(...)` returning `APPLIED`, `DUPLICATE`, `STALE`, or throwing on a sequence gap.

- [ ] **Step 1: Enable module dependencies**

Replace `modules/trading-messaging/build.gradle.kts` with:

```kotlin
plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    implementation(project(":modules:trading-application"))

    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation(testFixtures(project()))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}
```

- [ ] **Step 2: Write the issue's first failing test**

Create `DuplicatePartialFillDeliveryTest` with this public behavior:

```java
@Test
void duplicatePartialFillCreatesOneTradeAndOneLedgerPostingSet() {
    var partialFill = ContractFixturesV1.partialFillEnvelope();
    var projection = new FixtureDeliveryProjectionV1();

    assertThat(projection.accept(ContractFixturesV1.acceptedEnvelope())).isEqualTo(DeliveryResult.APPLIED);
    assertThat(projection.accept(partialFill)).isEqualTo(DeliveryResult.APPLIED);
    assertThat(projection.accept(partialFill)).isEqualTo(DeliveryResult.DUPLICATE);
    assertThat(projection.tradeCount()).isEqualTo(1);
    assertThat(projection.ledgerEntryCount()).isEqualTo(
        partialFill.payload().ledgerTransaction().entries().size()
    );
}
```

Create `TradingEnvelopeV1Test` to reject invalid required metadata and noncanonical decimals:

```java
assertThatThrownBy(() -> envelopeWithSchemaVersion(" ")).hasMessageContaining("schemaVersion");
assertThatThrownBy(() -> envelopeWithAggregateVersion(0)).hasMessageContaining("aggregateVersion");
assertThatThrownBy(() -> envelopeWithPayload(null)).hasMessageContaining("payload");
assertThatThrownBy(() -> new DecimalValueV1("1e3")).hasMessageContaining("decimal");
assertThatThrownBy(() -> new DecimalValueV1("1.20")).hasMessageContaining("canonical");
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```bash
bash ./gradlew :modules:trading-messaging:test --tests '*DuplicatePartialFillDeliveryTest'
```

Expected: compilation fails because `ContractFixturesV1`, `FixtureDeliveryProjectionV1`, and the v1 contract records do not exist.

- [ ] **Step 4: Implement the minimum envelope and value types**

Create these exact public shapes, using compact constructors that call `ContractValidationV1`:

```java
public record TradingEnvelopeV1<T>(
    String schemaVersion,
    String eventType,
    UUID eventId,
    Instant occurredAt,
    String producer,
    UUID correlationId,
    UUID causationId,
    String idempotencyKey,
    UUID aggregateId,
    long aggregateVersion,
    T payload
) {}

public record DecimalValueV1(String value) {
    public BigDecimal asBigDecimal() { return new BigDecimal(value); }
}

public record CurrencyAmountV1(String currency, DecimalValueV1 amount) {}
```

`ContractValidationV1` must provide `requiredText`, `required`, `positiveVersion`, `utcInstant`, `canonicalNonNegativeDecimal`, and `currencyCode`. Decimal validation accepts `0`, positive integers, and decimal fractions without sign, exponent, leading zeroes, or trailing zeroes.

- [ ] **Step 5: Implement minimum ledger, partial-fill, fixture, and projection types**

Use these exact nested public types:

```java
public final class LedgerContractV1 {
    public enum Direction { DEBIT, CREDIT }
    public record Entry(UUID entryId, String accountCode, Direction direction,
                        CurrencyAmountV1 amount, UUID sourceEventId) {}
    public record Transaction(UUID transactionId, UUID sourceEventId,
                              Instant postedAt, List<Entry> entries) {}
}

public final class OrderLifecycleContractV1 {
    public enum EventType { ACCEPTED, PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED, REJECTED }
    public record Event(UUID orderId, UUID intentId, UUID candidateId,
                        EventType type, DecimalValueV1 orderQuantity, DecimalValueV1 fillQuantity,
                        CurrencyAmountV1 fillPrice,
                        LedgerContractV1.Transaction ledgerTransaction) {}
}
```

`LedgerContractV1.Transaction` must copy its entry list, require at least two entries, reject duplicate entry IDs, require every entry source to match the transaction source, and compare debit and credit totals by currency. `ContractFixturesV1.partialFillEnvelope()` uses fixed UUIDs, a UTC timestamp, two balanced USD entries, and aggregate version 2 after accepted version 1. `FixtureDeliveryProjectionV1` declares `public enum DeliveryResult { APPLIED, DUPLICATE, STALE }`, stores applied event IDs and the latest version per aggregate, and exposes `DeliveryResult accept(TradingEnvelopeV1<?> envelope)`. Duplicate IDs return `DUPLICATE`, lower/equal old versions return `STALE`, the next version returns `APPLIED`, and a version gap throws `IllegalStateException`. Lifecycle projection also rejects overfills and post-terminal transitions.

- [ ] **Step 6: Run the focused test and verify GREEN**

Run:

```bash
bash ./gradlew :modules:trading-messaging:test --tests '*DuplicatePartialFillDeliveryTest'
```

Expected: one test passes and the duplicate delivery does not increment either count.

- [ ] **Step 7: Run module tests and commit**

```bash
bash ./gradlew :modules:trading-messaging:test
git add modules/trading-messaging
git commit -m "feat: define idempotent partial-fill contract"
```

---

### Task 2: Complete order, quantity, cost, and lifecycle validation

**Files:**
- Create: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/OrderExecutionContractV1.java`
- Modify: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/OrderLifecycleContractV1.java`
- Create: `modules/trading-messaging/src/test/java/com/idea2strategy/trading/messaging/contract/v1/OrderExecutionContractV1Test.java`
- Create: `modules/trading-messaging/src/test/java/com/idea2strategy/trading/messaging/contract/v1/LedgerContractV1Test.java`

**Interfaces:**
- Consumes: Task 1 value types and ledger transaction.
- Produces: order intent batches, policy metadata, complete lifecycle events, and constructor-enforced order/ledger invariants.

- [ ] **Step 1: Write failing validation tests**

Add focused tests with these assertions:

```java
assertThatCode(() -> validFractionalMarketDayIntent()).doesNotThrowAnyException();
assertThatThrownBy(() -> fractionalLimitIntent())
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessageContaining("fractional");
assertThatThrownBy(() -> notionalShortIntent())
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessageContaining("short");
assertThatThrownBy(() -> gtdIntentWithoutExpiry())
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessageContaining("expiry");
assertThatThrownBy(() -> unbalancedTransaction())
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessageContaining("balanced");
```

- [ ] **Step 2: Run the tests and verify RED**

```bash
bash ./gradlew :modules:trading-messaging:test --tests '*OrderExecutionContractV1Test' --tests '*LedgerContractV1Test'
```

Expected: compilation fails because `OrderExecutionContractV1` and the complete validation API do not exist.

- [ ] **Step 3: Implement execution types and rules**

Create these nested enums and records:

```java
public final class OrderExecutionContractV1 {
    public enum Side { BUY, SELL, SELL_SHORT, BUY_TO_COVER }
    public enum OrderType { MARKET, LIMIT, STOP, STOP_LIMIT, TRAILING_STOP }
    public enum TimeInForce { DAY, GTC, GTD }
    public enum QuantityMode { WHOLE_SHARES, FRACTIONAL_SHARES, NOTIONAL_AMOUNT }
    public enum IntentDecision { ACCEPTED, REDUCED, REJECTED }

    public record CostPolicy(String version, DecimalValueV1 feeRate,
                             DecimalValueV1 slippageRate) {}
    public record Intent(UUID intentId, UUID candidateId, UUID instrumentId,
                         Side side, OrderType orderType, OrderParameters orderParameters,
                         TimeInForce timeInForce,
                         Instant expiresAt, QuantityMode quantityMode,
                         DecimalValueV1 requestedQuantity,
                         DecimalValueV1 approvedQuantity,
                         IntentDecision decision, String reasonCode,
                         CostPolicy costPolicy) {}
    public record IntentBatch(UUID batchId, UUID botId, UUID evaluationId,
                              List<Intent> intents) {}
}
```

`OrderParameters` carries nullable `limitPrice`, `stopPrice`, and `trailPercent` fields with an exact type matrix: MARKET none, LIMIT only limit, STOP only stop, STOP_LIMIT both prices, TRAILING_STOP only trail in `(0, 1]`. Implement compact-constructor rules from Global Constraints. `IntentBatch` copies the list and rejects duplicate intent IDs. Expand lifecycle payloads with cancellation, expiration, and rejection reason codes while allowing fill fields and ledger transaction only on partial/final fill events.

- [ ] **Step 4: Run focused tests and verify GREEN**

```bash
bash ./gradlew :modules:trading-messaging:test --tests '*OrderExecutionContractV1Test' --tests '*LedgerContractV1Test'
```

Expected: all order-combination and ledger-balance tests pass.

- [ ] **Step 5: Run module tests and commit**

```bash
bash ./gradlew :modules:trading-messaging:test
git add modules/trading-messaging
git commit -m "feat: validate order execution contract"
```

---

### Task 3: Consume C's candidate batch and add accepted/reduced/rejected intent fixtures

**Files:**
- Consume without modifying: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/evaluation/OrderCandidate.java`
- Consume without modifying: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/evaluation/OrderCandidateBatch.java`
- Consume without modifying: `modules/trading-messaging/src/testFixtures/resources/contracts/v1/order-candidate-batch.json`
- Modify: `modules/trading-messaging/src/testFixtures/java/com/idea2strategy/trading/messaging/fixture/v1/ContractFixturesV1.java`
- Create: `modules/trading-messaging/src/test/java/com/idea2strategy/trading/messaging/contract/v1/UpstreamOrderCandidateCompatibilityTest.java`

**Interfaces:**
- Consumes: C's exact `OrderCandidateBatch(int schemaVersion, UUID batchId, UUID evaluationId, Instant createdAt, List<OrderCandidate> candidates)`.
- Produces: deterministic F intent fixtures with candidate and evaluation identity retained from the producer-owned type.

- [ ] **Step 1: Write the failing candidate fixture test**

```java
@Test
void loadsUpstreamFixtureAndRetainsCandidateIdentity() {
    var candidates = ContractJsonFixtureLoaderV1.readResource(
        "contracts/v1/order-candidate-batch.json",
        new TypeReference<OrderCandidateBatch>() {}
    );
    var intents = ContractFixturesV1.intentBatchFor(candidates).payload();

    assertThat(intents.evaluationId()).isEqualTo(candidates.evaluationId());
    assertThat(intents.intents()).allSatisfy(intent ->
        assertThat(candidates.candidates()).extracting(OrderCandidate::candidateId)
            .contains(intent.candidateId())
    );
}
```

- [ ] **Step 2: Run the test and verify RED**

```bash
bash ./gradlew :modules:trading-messaging:test --tests '*UpstreamOrderCandidateCompatibilityTest'
```

Expected: compilation fails because the upstream-resource loader and direct candidate-to-intent fixture method do not exist.

- [ ] **Step 3: Implement direct producer-type consumption and deterministic fixtures**

Do not create a parallel candidate record or COM-F candidate JSON resource. Load C's upstream fixture as `evaluation.OrderCandidateBatch`, map `OrderSide` explicitly to F's supported side, retain `candidateId`, `instrumentId`, and `evaluationId`, and use exact policy values `0.002` and `0.0005`. A future incompatible producer shape requires a clearly versioned adapter.

- [ ] **Step 4: Run focused and module tests**

```bash
bash ./gradlew :modules:trading-messaging:test --tests '*UpstreamOrderCandidateCompatibilityTest'
bash ./gradlew :modules:trading-messaging:test
```

Expected: candidate linkage and all prior tests pass.

- [ ] **Step 5: Commit**

```bash
git add modules/trading-messaging
git commit -m "fix: consume upstream order candidate contract"
```

---

### Task 4: Add settlement and canonical JSON fixtures

**Files:**
- Create: `modules/trading-messaging/src/main/java/com/idea2strategy/trading/messaging/contract/v1/SettlementContractV1.java`
- Create: `modules/trading-messaging/src/testFixtures/java/com/idea2strategy/trading/messaging/fixture/v1/ContractJsonFixtureLoaderV1.java`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/intent-batch.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/order-accepted.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/order-partial-fill.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/order-filled.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/order-cancelled.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/order-rejected.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/settlement-requested.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/settlement-failed.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/settlement-completed.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/order-intent-validation-matrix.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/ledger-transaction.json`
- Create: `modules/trading-messaging/src/testFixtures/resources/contracts/trading/v1/delivery-scenario.json`
- Create: `modules/trading-messaging/src/test/java/com/idea2strategy/trading/messaging/contract/v1/CanonicalJsonFixturesV1Test.java`
- Create: `modules/trading-messaging/src/test/java/com/idea2strategy/trading/messaging/contract/v1/OutOfOrderDeliveryTest.java`

**Interfaces:**
- Consumes: all Tasks 1-3 types and deterministic object fixtures.
- Produces: typed JSON fixture loading, normalized JSON tree equality, settlement messages, and explicit stale/gap examples.

- [ ] **Step 1: Write failing JSON round-trip and ordering tests**

```java
@ParameterizedTest
@MethodSource("canonicalFixtures")
void canonicalFixtureRoundTripsWithoutWireShapeDrift(String resource, TypeReference<?> type) {
    assertThat(ContractJsonFixtureLoaderV1.roundTrips(resource, type)).isTrue();
}

static Stream<Arguments> canonicalFixtures() {
    return Stream.of(
        Arguments.of("intent-batch.json", new TypeReference<TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch>>() {}),
        Arguments.of("order-accepted.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
        Arguments.of("order-partial-fill.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
        Arguments.of("order-filled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
        Arguments.of("order-cancelled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
        Arguments.of("order-rejected.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
        Arguments.of("settlement-requested.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}),
        Arguments.of("settlement-failed.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}),
        Arguments.of("settlement-completed.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}),
        Arguments.of("ledger-transaction.json", new TypeReference<TradingEnvelopeV1<LedgerContractV1.Transaction>>() {}),
        Arguments.of("delivery-scenario.json", new TypeReference<FixtureDeliveryProjectionV1.DeliveryScenario>() {})
    );
}

@Test
void staleIsIgnoredAndFutureGapIsRejected() {
    var projection = new FixtureDeliveryProjectionV1();
    assertThat(projection.accept(ContractFixturesV1.acceptedEnvelope())).isEqualTo(APPLIED);
    assertThat(projection.accept(ContractFixturesV1.staleAcceptedEnvelope())).isEqualTo(STALE);
    assertThatThrownBy(() -> projection.accept(ContractFixturesV1.futureGapEnvelope()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sequence gap");
}
```

- [ ] **Step 2: Run tests and verify RED**

```bash
bash ./gradlew :modules:trading-messaging:test --tests '*CanonicalJsonFixturesV1Test' --tests '*OutOfOrderDeliveryTest'
```

Expected: compilation or resource-loading failure because settlement, loader, and JSON resources do not exist.

- [ ] **Step 3: Implement settlement and JSON loading**

```java
public final class SettlementContractV1 {
    public enum EventType { REQUESTED, COMPLETED, FAILED }
    public record Event(UUID settlementId, UUID botId, EventType type,
                        String reasonCode, int attempt,
                        List<UUID> affectedOrderIds) {}
}
```

Require `attempt >= 1`, immutable affected-order IDs, a nonblank reason on FAILED, and no hidden default reason on other states. `ContractJsonFixtureLoaderV1` must construct a configured `ObjectMapper`, explicitly ignore unknown optional properties, keep unknown enums strict, read a classpath resource through a caller-provided `TypeReference<T>`, serialize the object, and compare source/output as `JsonNode` trees. Jackson types exposed by public fixture methods must be `testFixturesApi` dependencies. Add a malformed in-memory JSON case with `"type":"UNKNOWN"` and assert deserialization throws `JsonMappingException`. Add a fixture scan that collects every envelope `eventId` and asserts no duplicate IDs exist across canonical resources.

Add this nested resource model to `FixtureDeliveryProjectionV1`:

```java
public record DeliveryScenario(
    List<UUID> deliveryEventIds,
    int expectedTradeCount,
    int expectedLedgerEntryCount,
    DeliveryResult duplicateResult,
    DeliveryResult staleResult,
    String gapError
) {
    public DeliveryScenario {
        deliveryEventIds = List.copyOf(deliveryEventIds);
    }
}
```

- [ ] **Step 4: Create the canonical JSON resources**

Each F resource must match the deterministic UUIDs, timestamps, policy version, decimal strings, and enum spellings returned by `ContractFixturesV1`. The C candidate input remains only at upstream `contracts/v1/order-candidate-batch.json`. `delivery-scenario.json` contains ordered delivery event IDs plus expected `tradeCount`, `ledgerEntryCount`, duplicate result, stale result, and gap error text. Settlement resources form requested v1 → failed v2 → completed retry v3 with one settlement identity. The intent matrix executes valid and rejected combinations for all order types, DAY/GTC/GTD, and whole/fractional/notional modes.

Use the following constants for every Java and JSON fixture so cross-file identity is unambiguous:

```java
static final UUID BOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
static final UUID STRATEGY_VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
static final UUID EVALUATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
static final UUID INSTRUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
static final UUID CANDIDATE_BATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
static final UUID INTENT_BATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
static final UUID PARTIAL_FILL_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
static final UUID LEDGER_TRANSACTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
static final UUID SETTLEMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
static final Instant FIXTURE_TIME = Instant.parse("2026-07-31T14:30:00Z");
static final String COST_POLICY_VERSION = "virtual-fill-cost-v1";
```

Every envelope JSON uses this field order and naming; replace only the event identity, type, aggregate version, and typed payload values for the other resources:

```json
{
  "schemaVersion": "trading.v1",
  "eventType": "order.partially-filled",
  "eventId": "00000000-0000-0000-0000-000000000501",
  "occurredAt": "2026-07-31T14:30:00Z",
  "producer": "trading-worker",
  "correlationId": "00000000-0000-0000-0000-000000000801",
  "causationId": "00000000-0000-0000-0000-000000000411",
  "idempotencyKey": "partial-fill-00000000-0000-0000-0000-000000000501",
  "aggregateId": "00000000-0000-0000-0000-000000000401",
  "aggregateVersion": 2,
  "payload": {
    "orderId": "00000000-0000-0000-0000-000000000401",
    "intentId": "00000000-0000-0000-0000-000000000311",
    "candidateId": "00000000-0000-0000-0000-000000000211",
    "type": "PARTIALLY_FILLED",
    "orderQuantity": { "value": "10.5" },
    "fillQuantity": { "value": "2.5" },
    "fillPrice": { "currency": "USD", "amount": { "value": "100" } },
    "ledgerTransaction": {
      "transactionId": "00000000-0000-0000-0000-000000000601",
      "sourceEventId": "00000000-0000-0000-0000-000000000501",
      "postedAt": "2026-07-31T14:30:00Z",
      "entries": [
        { "entryId": "00000000-0000-0000-0000-000000000611", "accountCode": "SECURITY", "direction": "DEBIT", "amount": { "currency": "USD", "amount": { "value": "250" } }, "sourceEventId": "00000000-0000-0000-0000-000000000501" },
        { "entryId": "00000000-0000-0000-0000-000000000612", "accountCode": "CASH", "direction": "CREDIT", "amount": { "currency": "USD", "amount": { "value": "250" } }, "sourceEventId": "00000000-0000-0000-0000-000000000501" }
      ]
    }
  }
}
```

- [ ] **Step 5: Run focused and module tests**

```bash
bash ./gradlew :modules:trading-messaging:test --tests '*CanonicalJsonFixturesV1Test' --tests '*OutOfOrderDeliveryTest'
bash ./gradlew :modules:trading-messaging:test
```

Expected: all canonical resources round-trip as equal JSON trees and ordering behavior passes.

- [ ] **Step 6: Commit**

```bash
git add modules/trading-messaging
git commit -m "test: publish COM-F canonical JSON fixtures"
```

---

### Task 5: Verify the complete COM-F acceptance boundary

**Files:**
- Modify only files already created if verification reveals a defect.

**Interfaces:**
- Consumes: all COM-F contract types, fixtures, and tests.
- Produces: fresh module, repository, build, and scope evidence for review.

- [ ] **Step 1: Run focused acceptance tests**

```bash
bash ./gradlew :modules:trading-messaging:test \
  --tests '*DuplicatePartialFillDeliveryTest' \
  --tests '*OrderExecutionContractV1Test' \
  --tests '*LedgerContractV1Test' \
  --tests '*UpstreamOrderCandidateCompatibilityTest' \
  --tests '*OrderIntentValidationMatrixV1Test' \
  --tests '*CanonicalJsonFixturesV1Test' \
  --tests '*OutOfOrderDeliveryTest'
```

Expected: all COM-F tests pass with zero failures.

- [ ] **Step 2: Run the full repository verification**

```bash
bash ./gradlew test
bash ./gradlew build
```

Expected: every trading-engine test and build task succeeds.

- [ ] **Step 3: Verify issue scope and diff hygiene**

```bash
git diff --check origin/develop...HEAD
git diff --name-only origin/develop...HEAD
git status --short --branch
```

Expected: changes are limited to `docs/superpowers/**` and `modules/trading-messaging/**`; no uncommitted files remain after the final fix commit.

- [ ] **Step 4: Commit verification fixes only if necessary**

```bash
git add modules/trading-messaging docs/superpowers
git commit -m "test: verify COM-F messaging contract"
```

Skip this commit when verification required no file changes.
