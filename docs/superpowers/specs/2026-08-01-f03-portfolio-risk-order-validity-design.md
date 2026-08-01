# F03 Portfolio Risk and Order Validity Design

## Goal

Add a deterministic validation stage after F02 budget allocation. The validator confirms that a proposed order still fits the latest available-funds snapshot, does not impermissibly increase any independently configured portfolio risk limit, and satisfies the instrument's minimum amount, minimum quantity, and numeric precision policy.

## Scope

F03 validates one already-sized order proposal and returns `ACCEPTED`, `REJECTED`, or `REEVALUATION_REQUIRED` with stable reason codes. It performs no I/O, creates no order intent, persists no reservation, submits no broker order, and never creates a forced liquidation action.

F08A retains ownership of fractional-order eligibility and order-type, side, time-in-force combinations. F03 checks only numeric validity against an instrument policy that a caller has already selected. Exact risk formulas, minimums, and precision values remain configuration inputs rather than hidden defaults.

## Considered Approaches

### A. Pure domain validator (selected)

Place immutable inputs, results, and a pure validator in `trading-domain`. Live and backtest orchestration can reuse the same deterministic behavior, while later application work can assemble versioned account, risk, and instrument snapshots.

### B. Extend messaging and worker wiring now

Adding the snapshots to COM-C messages and invoking validation from the worker would make the feature immediately reachable, but it would cross upstream contract ownership before F90 and conflate F03 with F04 intent creation and F07 reservation lifecycle.

### C. Query account and instrument state during validation

A database-backed service could load fresh values itself, but the result would depend on timing between queries, replay would be harder, and policy decisions would become coupled to unfinished persistence models.

## Domain Model

`OrderValidityRequest` contains:

- proposal ID, quantity, price, and total required cash from the F02 decision;
- the funds snapshot version used by F02 and the latest funds snapshot version and available cash observed for F03;
- risk direction (`INCREASING` or `REDUCING`);
- zero or more independent risk evaluations, each with a stable metric code, policy version, current value, projected value, and maximum allowed value;
- an instrument numeric policy version with minimum notional, minimum quantity, maximum quantity scale, and maximum price scale.

All decimal values use `BigDecimal`. Collections are immutable copies. IDs and versions must be non-blank, amounts and limits must be non-negative, scales must be non-negative integers, and risk metric codes must be unique. Structurally malformed inputs fail fast with `IllegalArgumentException`; a missing business snapshot or policy is represented explicitly and produces a fail-safe decision instead of an exception.

`OrderValidityResult` contains the proposal ID, status, sorted reason codes, funds snapshot version, instrument policy version when present, and the sorted risk-policy versions that were evaluated. It never changes the proposed quantity, price, or cash amount.

## Validation Pipeline

Validation follows a fixed order but accumulates all applicable reasons. Output reasons and risk evidence are sorted so request list order cannot change the result.

### 1. Available funds

If the latest funds snapshot is absent, return `REEVALUATION_REQUIRED` with `AVAILABLE_FUNDS_UNAVAILABLE`. If its version differs from the version used by F02, return `REEVALUATION_REQUIRED` with `AVAILABLE_FUNDS_SNAPSHOT_CHANGED`. If the versions match but total required cash exceeds available cash, return `REEVALUATION_REQUIRED` with `INSUFFICIENT_AVAILABLE_FUNDS` so the caller can run F02 again against the new account boundary. F03 never silently scales the order.

### 2. Independent risk limits

Each risk metric is evaluated independently; there is no composite score. Missing or incomplete risk evaluation yields `REJECTED` with `RISK_EVALUATION_UNAVAILABLE`.

For an `INCREASING` proposal, any projected value above its maximum produces `REJECTED` with `RISK_LIMIT_EXCEEDED` and the metric code remains in the result evidence. For a `REDUCING` proposal, an already over-limit portfolio is allowed only when the projected value is less than or equal to the current value. A purported reducing proposal that increases any metric is rejected with `RISK_REDUCTION_NOT_CONFIRMED`. No result requests or implies forced selling.

### 3. Minimums and precision

Missing instrument numeric policy yields `REJECTED` with `INSTRUMENT_POLICY_UNAVAILABLE`. Otherwise:

- quantity below minimum yields `MINIMUM_QUANTITY_NOT_MET`;
- quantity multiplied by price below minimum notional yields `MINIMUM_NOTIONAL_NOT_MET`;
- quantity whose normalized non-negative scale exceeds the quantity maximum yields `QUANTITY_PRECISION_EXCEEDED`;
- price whose normalized non-negative scale exceeds the price maximum yields `PRICE_PRECISION_EXCEEDED`.

The validator never rounds. Callers must provide a value that already conforms to policy. Fractional eligibility and order-combination rules remain outside F03.

## Status Precedence

`REJECTED` has precedence over `REEVALUATION_REQUIRED`, which has precedence over `ACCEPTED`. This means a malformed business proposal is not made temporarily acceptable by a stale funds snapshot. An accepted result has no reason codes. A reevaluation result must not be converted into an order intent until F02 and F03 have rerun on one coherent snapshot.

## Failure and Safety Behavior

- Invalid primitive values, duplicate metric codes, or blank identifiers fail fast before validation.
- Missing funds state requests reevaluation rather than approving from cached data.
- Missing risk or instrument policy fails safe and does not invent default limits.
- Existing risk above a configured maximum never generates a sell. Only risk-increasing or falsely labeled reducing proposals are blocked.
- All checks use exact `BigDecimal` comparisons. No binary floating point, currency rounding, or implicit decimal truncation is permitted.
- The pure validator has no clocks, database access, message delivery, or mutable shared state.

## Integration Boundary

F03 adds only a `trading-domain` API and tests. A later application slice will assemble the request from the approved F02 decision, latest account snapshot, locked bot risk policy, current/projected risk evaluation, and versioned instrument metadata. F04 may create an idempotent order intent only from an accepted result. F07 owns durable reservation behavior, and F90 owns end-to-end worker integration.

## Verification

The first failing behavior test proves that a portfolio already above its gross-exposure limit rejects a risk-increasing proposal while an otherwise identical risk-reducing proposal is accepted when its projected exposure does not exceed its current exposure.

Additional unit tests cover:

- changed, unavailable, and insufficient available-funds snapshots;
- multiple independent risk metrics and input-order invariance;
- missing or incomplete risk evidence and instrument policies;
- reducing proposals that actually increase a metric;
- minimum quantity and minimum notional boundaries;
- quantity and price precision with normalized decimal scales;
- malformed negative values, blank versions, and duplicate metric codes;
- rejection precedence over reevaluation and an accepted result with no reasons.

The complete trading-engine test suite and trading-worker executable build must remain green before publication.
