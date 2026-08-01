# F02 Basic Budget Allocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic, cost-inclusive Basic buy-budget calculator that respects strategy caps and shared bot funds.

**Architecture:** Add immutable budget inputs, decisions, and a pure `BasicBudgetAllocator` to `trading-domain`. The allocator receives one complete snapshot, performs no I/O, sorts identifiers for reproducibility, and leaves persistence, risk validation, order-intent creation, and worker wiring to later F slices.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit Jupiter, `BigDecimal`

## Global Constraints

- Do not modify the COM-C candidate message, root `specs/**`, root `contracts/**`, or database schema.
- Allocate only new Basic buy candidates; sell and cancel processing is outside F02.
- Use the locked cost policy rates: expected buy slippage is applied before the fee, and the fee uses the slippage-adjusted amount.
- Use scale 18 and `RoundingMode.DOWN` for divisions that can repeat; never assign decimal remainder by iteration order.
- F03 owns portfolio risk, minimum amount and quantity, and instrument precision checks.
- All output ordering must be independent of request list order.

---

### Task 1: Validated budget input and result types

**Files:**
- Modify: `modules/trading-domain/build.gradle.kts`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicSizingMode.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicSizingPolicy.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/ExpectedCostPolicy.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicStrategyBudgetRequest.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocationRequest.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BudgetDecisionStatus.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BudgetReasonCode.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicBudgetDecision.java`
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocationResult.java`
- Test: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/budget/BasicBudgetModelTest.java`

**Interfaces:**
- Consumes: Java `UUID`, `BigDecimal`, and immutable `List` values.
- Produces: `BasicBudgetAllocationRequest`, `BasicStrategyBudgetRequest`, `BasicSizingPolicy`, `ExpectedCostPolicy`, `BasicBudgetDecision`, and `BasicBudgetAllocationResult` for Task 2.

- [ ] **Step 1: Add the JUnit test dependency**

Add the repository-standard test platform to `modules/trading-domain/build.gradle.kts`:

```kotlin
dependencies {
    api(project(":modules:trading-common"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 2: Write validation tests before the records exist**

Create `BasicBudgetModelTest` with literal inputs proving:

```java
@Test
void rejectsAValueRatioAboveOne() {
    assertThrows(IllegalArgumentException.class,
            () -> BasicSizingPolicy.availableBudgetRatio(new BigDecimal("1.01")));
}

@Test
void rejectsDuplicateCandidateIdsWithinAStrategy() {
    UUID duplicate = UUID.fromString("10000000-0000-0000-0000-000000000001");
    assertThrows(IllegalArgumentException.class, () -> new BasicStrategyBudgetRequest(
            UUID.fromString("20000000-0000-0000-0000-000000000002"),
            new BigDecimal("0.50"), BigDecimal.ZERO, BigDecimal.ZERO, true,
            BasicSizingPolicy.fixedAmount(new BigDecimal("1000")),
            List.of(duplicate, duplicate)));
}

@Test
void rejectsDuplicateStrategyIdsWithinARequest() {
    BasicStrategyBudgetRequest strategy = validStrategy();
    assertThrows(IllegalArgumentException.class, () -> new BasicBudgetAllocationRequest(
            new BigDecimal("10000"), new BigDecimal("8000"), BigDecimal.ZERO,
            new ExpectedCostPolicy("virtual-fill-cost-v1", new BigDecimal("0.002"), new BigDecimal("0.0005")),
            List.of(strategy, strategy)));
}
```

The production changes these tests catch are accepting an out-of-range ratio or ambiguous duplicate identifiers.

- [ ] **Step 3: Run the tests and verify RED**

Run:

```bash
bash ./gradlew :modules:trading-domain:test --tests '*BasicBudgetModelTest'
```

Expected: compilation fails because the budget types do not exist.

- [ ] **Step 4: Implement the immutable model**

Use these exact public signatures:

```java
public enum BasicSizingMode { FIXED_AMOUNT, AVAILABLE_BUDGET_RATIO }

public record BasicSizingPolicy(BasicSizingMode mode, BigDecimal value) {
    public BasicSizingPolicy {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0 || (mode == BasicSizingMode.AVAILABLE_BUDGET_RATIO
                && value.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("invalid Basic sizing value");
        }
    }

    public static BasicSizingPolicy fixedAmount(BigDecimal amount) {
        return new BasicSizingPolicy(BasicSizingMode.FIXED_AMOUNT, amount);
    }

    public static BasicSizingPolicy availableBudgetRatio(BigDecimal ratio) {
        return new BasicSizingPolicy(BasicSizingMode.AVAILABLE_BUDGET_RATIO, ratio);
    }
}

public record ExpectedCostPolicy(
        String version, BigDecimal feeRate, BigDecimal adverseBuySlippageRate) { }

public record BasicStrategyBudgetRequest(
        UUID strategyId,
        BigDecimal maximumEquityRatio,
        BigDecimal currentPositionMarketValue,
        BigDecimal reservedCash,
        boolean positionValuationComplete,
        BasicSizingPolicy sizingPolicy,
        List<UUID> candidateIds) { }

public record BasicBudgetAllocationRequest(
        BigDecimal totalEquity,
        BigDecimal grossAvailableCash,
        BigDecimal sharedReservedCash,
        ExpectedCostPolicy costPolicy,
        List<BasicStrategyBudgetRequest> strategies) { }

public enum BudgetDecisionStatus { ACCEPTED, REDUCED, REJECTED }

public enum BudgetReasonCode {
    STRATEGY_BUDGET_CAP,
    COMMON_FUNDS_PROPORTIONAL_REDUCTION,
    NO_AVAILABLE_STRATEGY_BUDGET,
    NO_AVAILABLE_SHARED_FUNDS,
    POSITION_VALUATION_UNAVAILABLE
}

public record BasicBudgetDecision(
        UUID strategyId,
        UUID candidateId,
        BigDecimal requestedCash,
        BigDecimal approvedPrincipal,
        BigDecimal expectedSlippage,
        BigDecimal expectedFee,
        BigDecimal totalRequiredCash,
        BudgetDecisionStatus status,
        List<BudgetReasonCode> reasonCodes,
        String costPolicyVersion) { }

public record BasicBudgetAllocationResult(
        BigDecimal spendableCash,
        List<BasicBudgetDecision> decisions) { }
```

Compact constructors must copy lists, reject nulls, reject negative monetary values and rates, enforce ratios in `[0, 1]`, reject blank policy versions, and reject duplicate strategy or candidate IDs.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the same focused Gradle command. Expected: all `BasicBudgetModelTest` tests pass.

- [ ] **Step 6: Commit the model**

```bash
git add modules/trading-domain/build.gradle.kts modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/budget/BasicBudgetModelTest.java
git commit -m "feat: add F02 budget model"
```

### Task 2: Equal, cap-aware, cost-inclusive allocation

**Files:**
- Create: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocator.java`
- Test: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocatorTest.java`

**Interfaces:**
- Consumes: `BasicBudgetAllocationRequest` from Task 1.
- Produces: `BasicBudgetAllocator.allocate(BasicBudgetAllocationRequest)` returning `BasicBudgetAllocationResult`.

- [ ] **Step 1: Write the first required failing behavior test**

Use total equity `10000`, gross cash `8000`, shared reservations `500`, a strategy cap of `50%`, position value `1000`, strategy reservations `500`, fixed requested cash `5000`, two candidate IDs, fee `0.002`, and slippage `0.0005`.

Assert the two sorted decisions are `REDUCED`, both contain `STRATEGY_BUDGET_CAP`, both receive equal `totalRequiredCash`, their aggregate required cash is at most `3500`, and each uses cost-policy version `virtual-fill-cost-v1`. Derive the expected per-candidate principal literally as:

```java
new BigDecimal("1750")
        .divide(new BigDecimal("1.0005").multiply(new BigDecimal("1.002")), 18, RoundingMode.DOWN)
```

The production change this test catches is ignoring position usage, reservations, costs, or equal allocation.

- [ ] **Step 2: Run the test and verify RED**

```bash
bash ./gradlew :modules:trading-domain:test --tests '*BasicBudgetAllocatorTest.equalAllocationIncludesCommittedUsageAndExpectedCosts'
```

Expected: compilation fails because `BasicBudgetAllocator` does not exist.

- [ ] **Step 3: Implement the minimal allocator**

Create a final `BasicBudgetAllocator` with `private static final int DIVISION_SCALE = 18` and the public method `BasicBudgetAllocationResult allocate(BasicBudgetAllocationRequest request)`. The method sorts strategy and candidate UUIDs, builds immutable per-strategy plans, and uses these formulas exactly:

```java
spendableCash = grossAvailableCash.subtract(sharedReservedCash).max(BigDecimal.ZERO);
strategyCap = totalEquity.multiply(maximumEquityRatio);
committedUsage = currentPositionMarketValue.add(reservedCash);
remainingBudget = strategyCap.subtract(committedUsage).max(BigDecimal.ZERO);
rawRequestedTotal = sizingMode == FIXED_AMOUNT
        ? sizingValue
        : remainingBudget.multiply(sizingValue);
capLimitedTotal = rawRequestedTotal.min(remainingBudget);
candidateRequestedCash = rawRequestedTotal.divide(candidateCount, 18, RoundingMode.DOWN);
candidateEnvelope = capLimitedTotal.divide(candidateCount, 18, RoundingMode.DOWN);
costMultiplier = BigDecimal.ONE.add(slippageRate).multiply(BigDecimal.ONE.add(feeRate));
approvedPrincipal = candidateEnvelope.divide(costMultiplier, 18, RoundingMode.DOWN);
expectedSlippage = approvedPrincipal.multiply(slippageRate);
expectedFee = approvedPrincipal.add(expectedSlippage).multiply(feeRate);
totalRequiredCash = approvedPrincipal.add(expectedSlippage).add(expectedFee);
```

Mark a decision `REDUCED` with `STRATEGY_BUDGET_CAP` when `rawRequestedTotal > remainingBudget`. Do not round money to cents.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the focused command from Step 2. Expected: one test passes.

- [ ] **Step 5: Add cap and sizing regression tests**

Add tests proving:

- `AVAILABLE_BUDGET_RATIO` value `0.25` allocates exactly one quarter of the remaining strategy budget;
- a position above its cap returns `REJECTED` with `NO_AVAILABLE_STRATEGY_BUDGET` and never forces a sell;
- an incomplete valuation returns `REJECTED` with `POSITION_VALUATION_UNAVAILABLE` only for that strategy;
- a strategy with no candidates emits no decisions and consumes no cash.

- [ ] **Step 6: Run all domain tests and commit**

```bash
bash ./gradlew :modules:trading-domain:test
git add modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocator.java modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocatorTest.java
git commit -m "feat: allocate Basic strategy budgets"
```

### Task 3: Shared-funds proportional reduction and determinism

**Files:**
- Modify: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocator.java`
- Modify: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocatorTest.java`

**Interfaces:**
- Consumes: all cap-limited strategy envelopes from Task 2.
- Produces: the same result API with shared-funds scaling and deterministic ordering.

- [ ] **Step 1: Write the proportional-reduction failing test**

Create two strategies whose cap-limited requests are `3000` and `1000`, with only `2000` bot spendable cash. Assert their aggregate required cash is at most `2000`, their approved envelopes retain the exact `3:1` proportion within scale-18 tolerance, every non-zero decision is `REDUCED`, and every decision contains `COMMON_FUNDS_PROPORTIONAL_REDUCTION`.

The production change this test catches is allowing the first strategy to consume cash before later strategies are considered.

- [ ] **Step 2: Verify RED**

```bash
bash ./gradlew :modules:trading-domain:test --tests '*BasicBudgetAllocatorTest.proportionallyReducesAllStrategiesWhenSharedCashIsShort'
```

Expected: the aggregate exceeds `2000` or the proportional reason is absent.

- [ ] **Step 3: Apply one shared factor before candidate cost calculation**

Compute:

```java
totalCapLimited = strategyPlans.stream().map(StrategyPlan::capLimitedTotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
sharedFactor = totalCapLimited.signum() == 0 || totalCapLimited.compareTo(spendableCash) <= 0
        ? BigDecimal.ONE
        : spendableCash.divide(totalCapLimited, 18, RoundingMode.DOWN);
approvedStrategyEnvelope = capLimitedTotal.multiply(sharedFactor);
```

Apply the same factor to every plan. Add `COMMON_FUNDS_PROPORTIONAL_REDUCTION` only when the factor is below one. When spendable cash is zero, reject otherwise valid candidates with `NO_AVAILABLE_SHARED_FUNDS`.

- [ ] **Step 4: Verify GREEN and add order-invariance coverage**

Run the focused test, then add a test that reverses both the strategy list and candidate lists and asserts the two complete results are equal. The allocator must sort by UUID before emitting decisions.

- [ ] **Step 5: Run all domain tests and commit**

```bash
bash ./gradlew :modules:trading-domain:test
git add modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocator.java modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/budget/BasicBudgetAllocatorTest.java
git commit -m "feat: proportionally reduce shared Basic funds"
```

### Task 4: Boundary audit and repository verification

**Files:**
- Modify only if a test exposes a defect: `modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget/*.java`
- Modify only if coverage needs a behavior assertion: `modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/budget/*.java`

**Interfaces:**
- Consumes: the completed F02 domain API.
- Produces: verification evidence suitable for Issue #14 and its Draft PR.

- [ ] **Step 1: Add aggregate-bound and malformed-input tests**

Use parameterized or separate tests to prove:

- aggregate `totalRequiredCash` never exceeds shared spendable cash;
- each strategy's aggregate `totalRequiredCash + committedUsage` never exceeds its cap;
- negative cash, negative position value, negative reservation, rates outside `[0, 1]`, a blank cost-policy version, and duplicate identifiers fail fast;
- zero shared cash and zero strategy capacity return explicit rejected decisions rather than negative amounts.

- [ ] **Step 2: Run focused tests without cache**

```bash
bash ./gradlew :modules:trading-domain:test --rerun-tasks
```

Expected: all budget model and allocator tests pass with zero failures.

- [ ] **Step 3: Run the full repository test suite and worker build**

```bash
bash ./gradlew clean test --rerun-tasks
bash ./gradlew :apps:trading-worker:bootJar
```

Expected: both commands exit zero.

- [ ] **Step 4: Audit the final diff against the design**

```bash
git diff --check origin/develop...HEAD
git diff --stat origin/develop...HEAD
git status -sb
```

Confirm the diff changes only the F02 design, plan, domain model, allocator, tests, and the domain test dependency. Confirm no COM-C contract, persistence migration, worker wiring, root protected file, or unrelated user change is present.

- [ ] **Step 5: Commit any verified final correction**

If Step 1 exposed a behavior defect, follow a fresh RED/GREEN cycle and commit only those corrected domain and test files:

```bash
git add modules/trading-domain/src/main/java/com/idea2strategy/trading/domain/budget modules/trading-domain/src/test/java/com/idea2strategy/trading/domain/budget
git commit -m "test: cover F02 budget boundaries"
```

If no correction is needed, do not create an empty commit.

- [ ] **Step 6: Request independent code review, then publish**

Use `origin/develop` as the review base and the branch HEAD as the review target. Resolve all Critical and Important findings with TDD, rerun Step 3, push `feature/14-basic-budget-allocation`, open a Draft PR against `develop`, and wait for required GitHub Actions checks.
