# F03 Portfolio Risk and Order Validity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic domain validator that rechecks available funds, independent portfolio risk limits, instrument minimums, and numeric precision after F02 allocation.

**Architecture:** Add immutable validation inputs, evidence, reasons, and a pure `OrderValidityValidator` under `trading-domain`. The validator receives complete versioned snapshots, performs no I/O or rounding, emits sorted deterministic evidence, and leaves message wiring, persistence, intent creation, reservations, and broker submission to later F work.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit Jupiter, `BigDecimal`

## Global Constraints

- Do not modify COM-C messages, root `specs/**`, root `contracts/**`, database schema, or existing F02 behavior.
- F03 never creates order intents, reservations, broker orders, forced liquidation actions, or silently resized proposals.
- F08A retains fractional eligibility and order-type, side, and time-in-force combinations.
- Exact risk formulas, limits, minimums, and precision values arrive as versioned inputs; do not add defaults.
- Use exact `BigDecimal` comparisons and normalized decimal scale; do not round or truncate values.
- Result reasons and risk evidence must be independent of request list order.
- Status precedence is `REJECTED`, then `REEVALUATION_REQUIRED`, then `ACCEPTED`.

---

### Task 1: Validated F03 input and result model

**Files:**
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/RiskDirection.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/AvailableFundsSnapshot.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/RiskMetricEvaluation.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/InstrumentNumericPolicy.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/OrderValidityRequest.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/OrderValidityStatus.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/OrderValidityReason.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/RiskPolicyEvidence.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/OrderValidityResult.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/OrderValidityInputValidation.java`
- Test: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/validation/OrderValidityModelTest.java`

**Interfaces:**
- Consumes: Java `UUID`, `String`, immutable `List`, and exact `BigDecimal` values.
- Produces: `OrderValidityRequest` for Task 2 and `OrderValidityResult` returned by the validator.

- [ ] **Step 1: Write model tests before the types exist**

Create `OrderValidityModelTest` with tests equivalent to:

```java
@Test
void rejectsDuplicateRiskMetricCodes() {
    RiskMetricEvaluation gross = metric("gross-exposure", "risk-v1", "90", "95", "100");
    assertThrows(IllegalArgumentException.class, () -> request(
            RiskDirection.INCREASING, true, List.of(gross, gross), validFunds(), validPolicy()));
}

@Test
void rejectsNegativeMoneyAndBlankVersions() {
    assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new AvailableFundsSnapshot(" ", BigDecimal.TEN)),
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new AvailableFundsSnapshot("funds-v1", new BigDecimal("-0.01"))),
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new InstrumentNumericPolicy("instrument-v1", BigDecimal.ONE,
                            BigDecimal.ONE, -1, 2)));
}

@Test
void copiesInputCollections() {
    ArrayList<RiskMetricEvaluation> metrics = new ArrayList<>();
    metrics.add(metric("gross-exposure", "risk-v1", "90", "95", "100"));
    OrderValidityRequest request = request(
            RiskDirection.INCREASING, true, metrics, validFunds(), validPolicy());
    metrics.clear();
    assertEquals(1, request.riskEvaluations().size());
}
```

The production change these tests catch is accepting ambiguous metric evidence or mutable/invalid snapshot data.

- [ ] **Step 2: Run the model tests and verify RED**

```bash
bash ./gradlew :modules:trading-domain:test --tests '*OrderValidityModelTest'
```

Expected: test compilation fails because the F03 validation types do not exist.

- [ ] **Step 3: Implement the immutable model**

Use these public shapes:

```java
public enum RiskDirection { INCREASING, REDUCING }

public record AvailableFundsSnapshot(String version, BigDecimal availableCash) { }

public record RiskMetricEvaluation(
        String metricCode,
        String policyVersion,
        BigDecimal currentValue,
        BigDecimal projectedValue,
        BigDecimal maximumAllowedValue) { }

public record InstrumentNumericPolicy(
        String version,
        BigDecimal minimumNotional,
        BigDecimal minimumQuantity,
        int maximumQuantityScale,
        int maximumPriceScale) { }

public record OrderValidityRequest(
        UUID proposalId,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal totalRequiredCash,
        String allocationFundsSnapshotVersion,
        AvailableFundsSnapshot latestFundsSnapshot,
        RiskDirection riskDirection,
        boolean riskEvaluationComplete,
        List<RiskMetricEvaluation> riskEvaluations,
        InstrumentNumericPolicy instrumentPolicy) { }

public enum OrderValidityStatus { ACCEPTED, REJECTED, REEVALUATION_REQUIRED }

public enum OrderValidityReason {
    AVAILABLE_FUNDS_UNAVAILABLE,
    AVAILABLE_FUNDS_SNAPSHOT_CHANGED,
    INSUFFICIENT_AVAILABLE_FUNDS,
    RISK_EVALUATION_UNAVAILABLE,
    RISK_LIMIT_EXCEEDED,
    RISK_REDUCTION_NOT_CONFIRMED,
    INSTRUMENT_POLICY_UNAVAILABLE,
    MINIMUM_QUANTITY_NOT_MET,
    MINIMUM_NOTIONAL_NOT_MET,
    QUANTITY_PRECISION_EXCEEDED,
    PRICE_PRECISION_EXCEEDED
}

public record RiskPolicyEvidence(String metricCode, String policyVersion) { }

public record OrderValidityResult(
        UUID proposalId,
        OrderValidityStatus status,
        List<OrderValidityReason> reasons,
        String fundsSnapshotVersion,
        String instrumentPolicyVersion,
        List<RiskPolicyEvidence> riskPolicyEvidence) { }
```

Compact constructors must copy lists, reject null required values, reject blank IDs and versions, reject negative amounts and risk values, reject negative maximum scales, and reject duplicate metric codes. `latestFundsSnapshot` and `instrumentPolicy` are the only nullable fields because their absence is a business state. `riskEvaluationComplete == true` with an empty metric list means no risk limits are configured; `false` means evidence is unavailable.

`OrderValidityResult` must require reasons to be sorted by enum order without duplicates, require evidence sorted by metric code then policy version without duplicates, and enforce that `ACCEPTED` has no reasons while non-accepted statuses have at least one reason.

- [ ] **Step 4: Run the focused model tests and verify GREEN**

```bash
bash ./gradlew :modules:trading-domain:test --tests '*OrderValidityModelTest'
```

Expected: all model tests pass.

- [ ] **Step 5: Commit the model**

```bash
git add modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/validation/OrderValidityModelTest.java
git commit -m "feat: add F03 order validity model"
```

### Task 2: Available-funds and independent risk validation

**Files:**
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/OrderValidityValidator.java`
- Test: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/validation/OrderValidityValidatorTest.java`

**Interfaces:**
- Consumes: `OrderValidityRequest` from Task 1.
- Produces: `OrderValidityValidator.validate(OrderValidityRequest)` returning `OrderValidityResult`.

- [ ] **Step 1: Write the first required failing risk behavior test**

Use an evaluation with current gross exposure `120`, projected increasing exposure `130`, and maximum `100`. Validate one `INCREASING` and one `REDUCING` request; use projected exposure `110` for the reducing request. Assert:

```java
assertEquals(OrderValidityStatus.REJECTED, increasing.status());
assertEquals(List.of(OrderValidityReason.RISK_LIMIT_EXCEEDED), increasing.reasons());
assertEquals(OrderValidityStatus.ACCEPTED, reducing.status());
assertTrue(reducing.reasons().isEmpty());
```

The production change this catches is either approving new risk above a limit or forcing an already over-limit portfolio to reach the limit immediately.

- [ ] **Step 2: Run the focused test and verify RED**

```bash
bash ./gradlew :modules:trading-domain:test --tests '*OrderValidityValidatorTest.blocksRiskIncreaseButAllowsRiskReductionAboveLimit'
```

Expected: compilation fails because `OrderValidityValidator` does not exist.

- [ ] **Step 3: Implement funds and risk validation**

Create a stateless final class:

```java
public final class OrderValidityValidator {
    public OrderValidityResult validate(OrderValidityRequest request) {
        // accumulate EnumSet<OrderValidityReason>, derive sorted evidence,
        // then apply REJECTED > REEVALUATION_REQUIRED > ACCEPTED precedence
    }
}
```

Apply these exact rules:

```text
latest funds absent                         -> AVAILABLE_FUNDS_UNAVAILABLE
latest version != allocation version       -> AVAILABLE_FUNDS_SNAPSHOT_CHANGED
same version and required cash > available -> INSUFFICIENT_AVAILABLE_FUNDS
risk evaluation incomplete                 -> RISK_EVALUATION_UNAVAILABLE
INCREASING and projected > maximum          -> RISK_LIMIT_EXCEEDED
REDUCING and projected > current            -> RISK_REDUCTION_NOT_CONFIRMED
```

The three funds reasons map to `REEVALUATION_REQUIRED`. The three risk reasons map to `REJECTED`. Include sorted `RiskPolicyEvidence` for every supplied evaluation, even when evaluation completeness is false, so the caller can audit what was observed.

- [ ] **Step 4: Run the required risk test and verify GREEN**

Run the focused command from Step 2. Expected: the test passes.

- [ ] **Step 5: Add funds, risk completeness, and order-invariance tests**

Add separate tests proving:

- absent latest funds yields reevaluation with `AVAILABLE_FUNDS_UNAVAILABLE`;
- a changed version yields reevaluation without silently changing quantity or cash;
- matching version with insufficient funds yields reevaluation;
- incomplete risk evaluation yields rejection;
- two independently configured metrics reject when either projected value exceeds its own maximum;
- a reducing proposal is rejected when any projected metric exceeds its current value;
- reversing risk evaluation order yields an equal result with sorted evidence;
- a risk rejection takes precedence over a simultaneous funds reevaluation reason while preserving both reasons.

- [ ] **Step 6: Run domain tests and commit**

```bash
bash ./gradlew :modules:trading-domain:test
git add modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/OrderValidityValidator.java modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/validation/OrderValidityValidatorTest.java
git commit -m "feat: validate F03 funds and risk limits"
```

### Task 3: Instrument minimums and exact numeric precision

**Files:**
- Modify: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/OrderValidityValidator.java`
- Modify: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/validation/OrderValidityValidatorTest.java`

**Interfaces:**
- Consumes: proposal quantity and price plus nullable `InstrumentNumericPolicy` from the Task 1 request.
- Produces: minimum and precision reasons through the Task 2 result API without changing proposal values.

- [ ] **Step 1: Write failing boundary tests**

Use a policy with minimum notional `10`, minimum quantity `0.01`, maximum quantity scale `4`, and maximum price scale `2`. Add tests asserting:

```text
quantity 0.009, price 200.00 -> MINIMUM_QUANTITY_NOT_MET
quantity 0.01, price 999.99  -> MINIMUM_NOTIONAL_NOT_MET
quantity 0.01001             -> QUANTITY_PRECISION_EXCEEDED
price 100.001                -> PRICE_PRECISION_EXCEEDED
quantity 0.0100, price 1000.00 -> ACCEPTED after normalized-scale checks
```

The production change these tests catch is accepting below-minimum orders, silently rounding over-precision values, or rejecting harmless trailing zeros.

- [ ] **Step 2: Run the boundary tests and verify RED**

```bash
bash ./gradlew :modules:trading-domain:test --tests '*OrderValidityValidatorTest.*Instrument*'
```

Expected: at least one assertion fails because instrument validation is absent.

- [ ] **Step 3: Implement exact numeric checks**

When the instrument policy is absent, add `INSTRUMENT_POLICY_UNAVAILABLE`. Otherwise calculate notional with `quantity.multiply(price)` and compare without rounding. Normalize scale exactly as:

```java
private static int normalizedScale(BigDecimal value) {
    return Math.max(0, value.stripTrailingZeros().scale());
}
```

Add the minimum and precision reason codes independently so one proposal may explain multiple violations. Never mutate quantity or price.

- [ ] **Step 4: Run boundary tests and verify GREEN**

Run the focused command from Step 2. Expected: all instrument tests pass.

- [ ] **Step 5: Add missing-policy and deterministic-reason tests**

Add tests proving an absent policy rejects with `INSTRUMENT_POLICY_UNAVAILABLE`, exact minimum values are accepted, and a proposal violating multiple rules returns reasons in enum order regardless of check order.

- [ ] **Step 6: Run domain tests and commit**

```bash
bash ./gradlew :modules:trading-domain:test --rerun-tasks
git add modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/OrderValidityValidator.java modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/validation/OrderValidityValidatorTest.java
git commit -m "feat: enforce F03 instrument validity"
```

### Task 4: Boundary audit and repository verification

**Files:**
- Modify only if a failing boundary exposes a defect: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation/*.java`
- Modify only for additional behavior evidence: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/validation/*.java`

**Interfaces:**
- Consumes: the completed F03 domain API.
- Produces: verification evidence suitable for Issue #24 and its Ready PR.

- [ ] **Step 1: Add malformed-input and status-invariant coverage**

Add tests for negative quantity, price, required cash, funds, current/projected/maximum risk values; blank allocation, funds, risk-policy, instrument-policy versions; negative precision scales; duplicate metric codes; accepted results with reasons; and non-accepted results without reasons. Each must fail fast with `IllegalArgumentException` or `NullPointerException` at model construction as appropriate.

- [ ] **Step 2: Run focused tests without cache**

```bash
bash ./gradlew :modules:trading-domain:test --rerun-tasks
```

Expected: all F02 and F03 domain tests pass with zero failures.

- [ ] **Step 3: Run the full repository suite and worker build**

```bash
bash ./gradlew clean test --rerun-tasks
bash ./gradlew :apps:trading-worker:bootJar --rerun-tasks
```

Expected: both commands exit zero.

- [ ] **Step 4: Audit the final diff**

```bash
git diff --check origin/develop...HEAD
git diff --stat origin/develop...HEAD
git status -sb
```

Confirm the diff contains only the F03 design, plan, domain validation model, validator, and tests. Confirm there is no messaging, worker, persistence, database, protected-root, or unrelated change.

- [ ] **Step 5: Commit any verified final correction**

If the boundary audit exposed a behavior defect, follow a fresh RED/GREEN cycle and commit only the corrected validation and test files:

```bash
git add modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/validation modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/validation
git commit -m "test: cover F03 validation boundaries"
```
