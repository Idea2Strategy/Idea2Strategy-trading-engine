package com.idea2strategy.trading.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.strategy.runtime.basic.BasicDecisionStatus;
import com.idea2strategy.trading.strategy.runtime.plan.BasicPlanInterpreter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ExecutionGateTest {

    private static final Instant FIRST = Instant.parse("2026-08-03T14:00:00Z");

    @Test
    void oneShotAndConditionRearmModesEnforceTheirExecutionBoundaries() {
        var once = new EvaluatingBotRuntime.ExecutionGate(tradingSessions());
        var rearm = new EvaluatingBotRuntime.ExecutionGate(tradingSessions());
        var oncePolicy = new BasicPlanInterpreter.ExecutionPolicy(
                100, "1회만", "조건 재충족", 1, 10);
        var rearmPolicy = new BasicPlanInterpreter.ExecutionPolicy(
                100, "대기 후 재진입", "조건 재충족", 1, 2);

        assertTrue(once.accepts(BasicDecisionStatus.CANDIDATE, oncePolicy, FIRST));
        assertFalse(once.accepts(BasicDecisionStatus.CANDIDATE, oncePolicy, FIRST.plusSeconds(60)));

        assertTrue(rearm.accepts(BasicDecisionStatus.CANDIDATE, rearmPolicy, FIRST));
        assertFalse(rearm.accepts(BasicDecisionStatus.CANDIDATE, rearmPolicy, FIRST.plusSeconds(60)));
        assertFalse(rearm.accepts(
                BasicDecisionStatus.CONDITION_NOT_MET, rearmPolicy, FIRST.plusSeconds(120)));
        assertTrue(rearm.accepts(BasicDecisionStatus.CANDIDATE, rearmPolicy, FIRST.plusSeconds(180)));
        assertFalse(rearm.accepts(BasicDecisionStatus.CANDIDATE, rearmPolicy, FIRST.plusSeconds(240)));
    }

    @Test
    void barWaitCountsCompletedEvaluationsBeforeReentry() {
        var gate = new EvaluatingBotRuntime.ExecutionGate(tradingSessions());
        var policy = new BasicPlanInterpreter.ExecutionPolicy(
                25, "대기 후 재진입", "N봉 이후", 2, 3);

        assertTrue(gate.accepts(BasicDecisionStatus.CANDIDATE, policy, FIRST));
        assertFalse(gate.accepts(BasicDecisionStatus.CANDIDATE, policy, FIRST.plusSeconds(60)));
        assertTrue(gate.accepts(BasicDecisionStatus.CANDIDATE, policy, FIRST.plusSeconds(120)));
    }

    @Test
    void restoredGateFailsClosedUntilItsWaitConditionIsObservedAgain() {
        var gate = new EvaluatingBotRuntime.ExecutionGate(
                new EvaluatingBotRuntime.ExecutionGateSnapshot(1, FIRST), tradingSessions());
        var policy = new BasicPlanInterpreter.ExecutionPolicy(
                50, "대기 후 재실행", "조건 재충족", 1, 3);

        assertFalse(gate.accepts(
                BasicDecisionStatus.CANDIDATE, policy, FIRST.plusSeconds(30)));
        assertFalse(gate.accepts(
                BasicDecisionStatus.CONDITION_NOT_MET, policy, FIRST.plusSeconds(60)));
        assertTrue(gate.accepts(
                BasicDecisionStatus.CANDIDATE, policy, FIRST.plusSeconds(90)));
    }

    @Test
    void tradingDayWaitExcludesAWeekdayExchangeHoliday() {
        var gate = new EvaluatingBotRuntime.ExecutionGate(tradingSessions());
        var policy = new BasicPlanInterpreter.ExecutionPolicy(
                25, "대기 후 재진입", "N거래일 이후", 2, 3);
        Instant christmasEve = Instant.parse("2025-12-24T21:00:00Z");

        assertTrue(gate.accepts(BasicDecisionStatus.CANDIDATE, policy, christmasEve));
        assertFalse(gate.accepts(
                BasicDecisionStatus.CANDIDATE,
                policy,
                Instant.parse("2025-12-26T21:00:00Z")));
        assertTrue(gate.accepts(
                BasicDecisionStatus.CANDIDATE,
                policy,
                Instant.parse("2025-12-29T21:00:00Z")));
    }

    private static EvaluatingBotRuntime.TradingSessionCounter tradingSessions() {
        ZoneId market = ZoneId.of("America/New_York");
        return (start, end) -> {
            LocalDate cursor = start.atZone(market).toLocalDate();
            LocalDate through = end.atZone(market).toLocalDate();
            long sessions = 0;
            while (cursor.isBefore(through)) {
                cursor = cursor.plusDays(1);
                boolean weekday = cursor.getDayOfWeek().getValue() <= 5;
                if (weekday && !cursor.equals(LocalDate.of(2025, 12, 25))) {
                    sessions++;
                }
            }
            return sessions;
        };
    }
}
