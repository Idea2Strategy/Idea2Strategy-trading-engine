package com.idea2strategy.trading.strategy.runtime.evaluation;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Runs accepted evaluations in FIFO order per bot while allowing separate bots to use the supplied executor
 * concurrently. The queue does not own the executor and closing it never cancels already accepted work.
 */
public final class PerBotEvaluationQueue implements AutoCloseable {
    private final Object monitor = new Object();
    private final Executor executor;
    private final Map<UUID, ArrayDeque<QueuedEvaluation<?>>> lanes = new HashMap<>();
    private boolean accepting = true;

    public PerBotEvaluationQueue(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    public <T> CompletionStage<T> submit(UUID botId, Supplier<? extends T> evaluation) {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(evaluation, "evaluation must not be null");

        QueuedEvaluation<T> queued = new QueuedEvaluation<>(botId, evaluation);
        boolean startsLane;
        synchronized (monitor) {
            if (!accepting) {
                throw new RejectedExecutionException("per-bot evaluation queue is closed");
            }
            ArrayDeque<QueuedEvaluation<?>> lane = lanes.computeIfAbsent(botId, ignored -> new ArrayDeque<>());
            lane.addLast(queued);
            startsLane = lane.size() == 1;
        }

        if (startsLane) {
            schedule(queued);
        }
        return queued.completion.copy();
    }

    @Override
    public void close() {
        synchronized (monitor) {
            accepting = false;
        }
    }

    private void schedule(QueuedEvaluation<?> queued) {
        try {
            executor.execute(() -> run(queued));
        } catch (RuntimeException failure) {
            queued.completion.completeExceptionally(failure);
            advance(queued);
        }
    }

    private <T> void run(QueuedEvaluation<T> queued) {
        try {
            queued.completion.complete(queued.evaluation.get());
        } catch (Throwable failure) {
            queued.completion.completeExceptionally(failure);
        } finally {
            advance(queued);
        }
    }

    private void advance(QueuedEvaluation<?> completed) {
        QueuedEvaluation<?> next = null;
        synchronized (monitor) {
            ArrayDeque<QueuedEvaluation<?>> lane = lanes.get(completed.botId);
            if (lane == null || lane.peekFirst() != completed) {
                return;
            }
            lane.removeFirst();
            if (lane.isEmpty()) {
                lanes.remove(completed.botId, lane);
            } else {
                next = lane.peekFirst();
            }
        }
        if (next != null) {
            schedule(next);
        }
    }

    private static final class QueuedEvaluation<T> {
        private final UUID botId;
        private final Supplier<? extends T> evaluation;
        private final CompletableFuture<T> completion = new CompletableFuture<>();

        private QueuedEvaluation(UUID botId, Supplier<? extends T> evaluation) {
            this.botId = botId;
            this.evaluation = evaluation;
        }
    }
}
