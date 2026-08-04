package com.idea2strategy.trading.strategy.runtime.control;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The span of market time a bot may be evaluated in.
 *
 * <p>A personal bot's window is open-ended: it starts when its owner starts it and closes only when a
 * stop command arrives. A room bot's window is the room's evaluation schedule, and both ends of it are
 * product meaning — a decision made a moment after the room stopped counting is not a late trade, it
 * is a trade that belongs to no room and would still reach the shared canonical ledger the room's
 * performance is read from.
 *
 * <p>The end is carried by the command that opens the window rather than enforced by a stop arriving
 * on time. C93 asks the evaluation runtime to <em>follow</em> the boundary, and a boundary that holds
 * only while another service's scheduler is punctual is not followed, it is hoped for. With the end in
 * hand the runtime is fail-closed: once market time reaches it, no event is evaluated, whether or not
 * the stop has been delivered yet.
 *
 * <p>The end is exclusive. A room's evaluation ends <em>at</em> that instant, so an event stamped
 * exactly there is the first one outside the window.
 */
public record EvaluationWindow(Instant eligibleFrom, Instant eligibleUntil) {

    public EvaluationWindow {
        Objects.requireNonNull(eligibleFrom, "eligibleFrom");
        if (eligibleUntil != null && !eligibleUntil.isAfter(eligibleFrom)) {
            throw new IllegalArgumentException(
                    "an evaluation window that ends before it opens can never evaluate anything: "
                            + eligibleFrom + " .. " + eligibleUntil);
        }
    }

    /** A personal bot's window: open from its eligibility, closed only by a stop. */
    public static EvaluationWindow openEndedFrom(Instant eligibleFrom) {
        return new EvaluationWindow(eligibleFrom, null);
    }

    /** The instant the window closes, absent for a bot no schedule bounds. */
    public Optional<Instant> end() {
        return Optional.ofNullable(eligibleUntil);
    }

    /** Whether an event observed at this market time falls inside the window. */
    public boolean admits(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (observedAt.isBefore(eligibleFrom)) {
            return false;
        }
        return eligibleUntil == null || observedAt.isBefore(eligibleUntil);
    }
}
