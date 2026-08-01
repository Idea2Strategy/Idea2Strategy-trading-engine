# F02 Basic Budget Allocation Design

## Goal

Add a deterministic budget calculator for new Basic buy candidates. The calculator applies each strategy's total-equity ratio cap, current position usage, open-order reservations, expected virtual execution costs, equal candidate allocation, and exceptional proportional reduction when shared bot funds are insufficient.

## Scope

F02 calculates budget decisions only. It does not create order intents, persist reservations, execute fills, update positions, or post ledger entries. Sell and cancel candidates do not consume new buy budget and remain outside this calculator; their position and order-validity checks belong to later F work.

The COM-C candidate message is not changed. It does not currently carry the locked strategy budget configuration or strategy attribution needed by F02. The calculator therefore exposes a dependency-neutral domain request that a later application adapter can assemble from the candidate batch and locked bot snapshot without introducing a C-to-F contract change.

## Considered Approaches

### A. Pure domain calculator (selected)

Keep the allocation algorithm in `trading-domain` and pass it a complete immutable snapshot. This makes the same calculation reusable by live and backtest orchestration, allows direct deterministic tests, and avoids coupling product rules to persistence or message delivery.

### B. Extend the COM-C candidate contract now

Adding strategy IDs and budget policies to the upstream message would permit immediate worker wiring, but it would change another area owner's contract before the F90 integration slice and couple evaluation output to account state that F owns.

### C. Calculate through database queries

A query-centric service could load positions and reservations and calculate allocations in SQL. It would mix snapshot acquisition with policy decisions, make replay testing harder, and bind F02 to the unfinished F07 persistence model.

## Domain Model

`BasicBudgetAllocationRequest` contains one bot-level snapshot and one or more strategy allocation requests:

- bot total equity;
- gross available cash before open-order reservations;
- shared open-order reserved cash;
- a cost policy version, fee rate, and adverse buy slippage rate;
- strategy ID, maximum total-equity ratio, current attributed position market value, attributed open-order reserved cash, and position-valuation completeness;
- a locked Basic sizing policy (`FIXED_AMOUNT` or `AVAILABLE_BUDGET_RATIO`);
- the candidate IDs eligible for equal allocation.

Money and ratios use `BigDecimal`. Amounts and rates must be non-negative, ratios must not exceed one, IDs must be unique within their scope, and at least one cost-policy version character must be present. Structurally invalid snapshots fail fast with `IllegalArgumentException`. An incomplete current-position valuation is a valid business state and rejects only that strategy's new buy candidates with `POSITION_VALUATION_UNAVAILABLE`.

`BasicBudgetAllocationResult` returns the bot spendable cash and one decision per candidate. Each decision contains the strategy ID, candidate ID, requested cash envelope, approved principal, expected slippage, expected fee, total required cash, decision (`ACCEPTED`, `REDUCED`, or `REJECTED`), reason codes, and cost-policy version.

## Calculation

The calculator first sorts strategies by strategy ID and candidates by candidate ID. Sorting is an implementation detail for reproducible output; it never grants priority.

Bot spendable cash is:

```text
max(0, gross available cash - shared open-order reserved cash)
```

For each strategy with complete valuation, its remaining budget is:

```text
strategy cap       = bot total equity × maximum ratio
committed usage    = current position market value + attributed open-order reserved cash
remaining budget   = max(0, strategy cap - committed usage)
```

The sizing policy produces a desired total cash envelope:

```text
FIXED_AMOUNT             = min(locked fixed amount, remaining budget)
AVAILABLE_BUDGET_RATIO   = remaining budget × locked ratio
```

The envelope includes expected execution cost. It is divided equally among all eligible candidates in the strategy. The buy cost multiplier is:

```text
(1 + adverse slippage rate) × (1 + fee rate)
```

For each candidate cash envelope, approved principal is divided by that multiplier at scale 18 with `RoundingMode.DOWN`. Expected slippage is `principal × slippage rate`; expected fee is `(principal + expected slippage) × fee rate`. Total required cash is the sum of principal, slippage, and fee and therefore cannot exceed the candidate envelope. Any sub-decimal remainder stays unallocated rather than being assigned by list order.

If the sum of all strategies' desired cash envelopes exceeds bot spendable cash, every non-zero envelope receives the same proportional factor:

```text
shared factor = bot spendable cash / total desired cash envelopes
```

This is the only Basic shared-funds reduction rule. It preserves equal allocation within each strategy and prevents delivery or iteration order from selecting a winner. Applying a strategy cap adds `STRATEGY_BUDGET_CAP`; applying the shared factor adds `COMMON_FUNDS_PROPORTIONAL_REDUCTION`. No remaining strategy budget adds `NO_AVAILABLE_STRATEGY_BUDGET`, and no shared cash adds `NO_AVAILABLE_SHARED_FUNDS`.

## Failure and Safety Behavior

- Missing or invalid bot-wide monetary state fails the whole calculation; the caller must not submit orders from a partial result.
- Missing current valuation rejects only the affected strategy because its cap usage cannot be trusted.
- Existing positions above a cap are not sold. The remaining budget becomes zero, so only new risk-increasing allocation is blocked.
- Open-order reservations reduce both their attributed strategy capacity and the shared cash pool. The request assembler must provide the same reservation snapshot boundary for both values; the calculator never queries mutable state during a run.
- Negative amounts, ratios outside zero through one, duplicate IDs, or blank policy versions are rejected as malformed input.
- Zero candidates produce an empty strategy result and consume no budget.
- Arithmetic always rounds approved principal down, so cost-inclusive allocations do not exceed either a strategy cap or shared cash.

## Integration Boundary

F02 adds no persistence schema and does not modify `CandidateBatchProcessor`. A later application slice assembles the calculator request from the locked bot configuration, strategy attribution, current market-valued positions, active reservations, and the candidate batch before order-intent creation. F03 then applies portfolio risk, minimum amount and quantity, and instrument precision checks. F04 converts surviving decisions into idempotent order intents, and F07 owns durable reservation lifecycle.

## Verification

The first failing test proves that two candidates split the remaining cost-inclusive strategy budget equally after position usage and reservation deductions, without exceeding the available total.

Additional unit tests cover:

- fixed-amount and available-budget-ratio sizing;
- position usage above the strategy cap without forced reduction of the position;
- incomplete position valuation rejecting only the affected strategy;
- expected slippage and fee calculation using the locked policy version;
- proportional reduction across multiple strategies when shared cash is short;
- input-order invariance;
- zero shared funds and zero remaining strategy budget;
- malformed amounts, ratios, policy versions, and duplicate identifiers;
- aggregate required cash staying within both per-strategy and bot limits after decimal rounding.

The complete trading-engine test suite and trading-worker build must remain green before publication.
