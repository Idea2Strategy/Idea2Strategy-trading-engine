package com.idea2strategy.trading.application.order;

import java.time.Instant;
import java.util.UUID;

public sealed interface OrderLifecycleCommand
        permits FillOrderCommand, CancelOrderCommand, ExpireOrderCommand {
    UUID commandId();

    UUID orderId();

    long expectedVersion();

    Instant occurredAt();
}
