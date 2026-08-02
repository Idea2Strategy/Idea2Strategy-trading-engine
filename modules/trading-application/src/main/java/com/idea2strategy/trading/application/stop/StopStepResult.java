package com.idea2strategy.trading.application.stop;

import com.idea2strategy.trading.domain.stop.SystemCloseAction;
import java.util.List;

/**
 * What one stop settlement step reports back.
 *
 * <p>{@code closeActions} is the store boundary wrapper for the extra fact canonical storage
 * demands and a status alone cannot supply. {@code trading.system_close_actions} needs the
 * partition, flow, instrument, quantity and generated intent of every forced close, so the
 * liquidation step hands those over here rather than widening
 * {@link com.idea2strategy.trading.application.port.PositionLiquidationPort}, whose contract stays
 * a single status. Only the liquidation step may carry them.
 */
public record StopStepResult(
        StopStepResultStatus status, String detail, List<SystemCloseAction> closeActions) {

    public StopStepResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        closeActions = closeActions == null ? List.of() : List.copyOf(closeActions);
    }

    public StopStepResult(StopStepResultStatus status, String detail) {
        this(status, detail, List.of());
    }

    public static StopStepResult completed(String detail) {
        return new StopStepResult(StopStepResultStatus.COMPLETED, detail);
    }

    /** Completed, and the forced closes the step generated are recorded with it. */
    public static StopStepResult completed(String detail, List<SystemCloseAction> closeActions) {
        return new StopStepResult(StopStepResultStatus.COMPLETED, detail, closeActions);
    }

    public static StopStepResult partial(String detail) {
        return new StopStepResult(StopStepResultStatus.PARTIAL, detail);
    }

    /** Partially completed, and the forced closes it did generate are recorded with it. */
    public static StopStepResult partial(String detail, List<SystemCloseAction> closeActions) {
        return new StopStepResult(StopStepResultStatus.PARTIAL, detail, closeActions);
    }

    public static StopStepResult retryable(String detail) {
        return new StopStepResult(StopStepResultStatus.RETRYABLE, detail);
    }

    public static StopStepResult terminalFailure(String detail) {
        return new StopStepResult(StopStepResultStatus.TERMINAL_FAILURE, detail);
    }
}
