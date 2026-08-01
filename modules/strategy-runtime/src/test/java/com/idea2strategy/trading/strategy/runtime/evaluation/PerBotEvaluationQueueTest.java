package com.idea2strategy.trading.strategy.runtime.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PerBotEvaluationQueueTest {
    private static final UUID BOT_ID = UUID.fromString("26c06076-f4f9-4364-83b1-35451dbbf8e0");

    @Test
    void queuesSameBotWithoutStartingOrCancellingBehindInFlightEvaluation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            PerBotEvaluationQueue queue = new PerBotEvaluationQueue(executor);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            List<String> completions = new CopyOnWriteArrayList<>();

            var first = queue.submit(BOT_ID, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                completions.add("first");
                return "first-result";
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            var second = queue.submit(BOT_ID, () -> {
                secondStarted.countDown();
                completions.add("second");
                return "second-result";
            });

            assertFalse(secondStarted.await(150, TimeUnit.MILLISECONDS));
            assertFalse(first.toCompletableFuture().isCancelled());

            releaseFirst.countDown();
            assertEquals("first-result", first.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals("second-result", second.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(List.of("first", "second"), completions);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void allowsDifferentBotsToEvaluateConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            PerBotEvaluationQueue queue = new PerBotEvaluationQueue(executor);
            CountDownLatch bothStarted = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);

            var firstBot = queue.submit(BOT_ID, () -> {
                bothStarted.countDown();
                await(release);
                return "first-bot";
            });
            var secondBot = queue.submit(
                    UUID.fromString("2ed3459a-13ea-4b93-ae02-92d7861d029e"),
                    () -> {
                        bothStarted.countDown();
                        await(release);
                        return "second-bot";
                    });

            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("first-bot", firstBot.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals("second-bot", secondBot.toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void continuesWithNextSameBotEvaluationAfterFailure() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PerBotEvaluationQueue queue = new PerBotEvaluationQueue(executor);

            var failed = queue.submit(BOT_ID, () -> {
                throw new IllegalStateException("evaluation failed");
            });
            var following = queue.submit(BOT_ID, () -> "following-result");

            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> failed.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals("evaluation failed", exception.getCause().getMessage());
            assertEquals("following-result", following.toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void callerCancellationDoesNotCancelAcceptedEvaluationOrItsFollowers() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PerBotEvaluationQueue queue = new PerBotEvaluationQueue(executor);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch cancelledSubmissionRan = new CountDownLatch(1);

            var first = queue.submit(BOT_ID, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return "first";
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            var callerView = queue.submit(BOT_ID, () -> {
                cancelledSubmissionRan.countDown();
                return "still-ran";
            });

            assertTrue(callerView.toCompletableFuture().cancel(true));
            releaseFirst.countDown();

            assertEquals("first", first.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(cancelledSubmissionRan.await(2, TimeUnit.SECONDS));
            assertEquals(
                    "following",
                    queue.submit(BOT_ID, () -> "following").toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closeRejectsOnlyNewSubmissionsAndDrainsAcceptedWork() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PerBotEvaluationQueue queue = new PerBotEvaluationQueue(executor);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            var first = queue.submit(BOT_ID, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return "first";
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            var accepted = queue.submit(BOT_ID, () -> "accepted-before-close");

            queue.close();

            assertThrows(RejectedExecutionException.class, () -> queue.submit(BOT_ID, () -> "rejected"));
            releaseFirst.countDown();
            assertEquals("first", first.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals("accepted-before-close", accepted.toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted", exception);
        }
    }
}
