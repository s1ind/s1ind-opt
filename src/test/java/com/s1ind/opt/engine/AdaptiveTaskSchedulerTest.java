package com.s1ind.opt.engine;

import com.s1ind.opt.engine.AdaptiveTaskScheduler.SubmissionResult;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.SubmissionStatus;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskHandle;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskPriority;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveTaskSchedulerTest {

    private AdaptivePerformanceEngine ape;
    private AdaptiveTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        ape = AdaptivePerformanceEngine.getInstance();
        ape.stopBackgroundWorker();
        ape.resetForTesting();
        scheduler = AdaptiveTaskScheduler.getInstance();
        scheduler.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        scheduler.resetForTesting();
        ape.resetForTesting();
    }

    private void forceCriticalStress() {
        long t0 = 1_000_000_000L;
        for (int i = 0; i < 40; i++) ape.recordTick(49_000_000L);
        ape.recalculateNow(t0);
        ape.recalculateNow(t0 + 4_000_000_000L);
        assertEquals(StressLevel.CRITICAL, ape.getStressLevel());
    }

    @Test
    void testHighExecutesBeforeNormalAndLowWithValidHandles() {
        scheduler.enableManualSteppingForTesting();
        List<String> order = new ArrayList<>();
        TaskHandle hLow = scheduler.submit(() -> order.add("LOW"), TaskPriority.LOW).handle();
        TaskHandle hNorm = scheduler.submit(() -> order.add("NORMAL"), TaskPriority.NORMAL).handle();
        TaskHandle hHigh = scheduler.submit(() -> order.add("HIGH"), TaskPriority.HIGH).handle();

        long now = System.nanoTime();
        while (scheduler.stepOnceForTesting(now)) {
            now += 200_000_000L;
        }

        assertEquals(List.of("HIGH", "NORMAL", "LOW"), order);
        assertEquals(TaskState.COMPLETED, hHigh.getState());
        assertEquals(TaskState.COMPLETED, hNorm.getState());
        assertEquals(TaskState.COMPLETED, hLow.getState());
    }

    @Test
    void testUniqueVsAttemptDeferralCountersUnderCriticalStress() {
        forceCriticalStress();
        scheduler.enableManualSteppingForTesting();

        AtomicInteger lowDone = new AtomicInteger(0);
        scheduler.submit(lowDone::incrementAndGet, TaskPriority.LOW, "unique-job");

        long now = System.nanoTime();
        // Deferred 3 consecutive polling cycles for the same single task
        assertFalse(scheduler.stepOnceForTesting(now + 100_000_000L));
        assertFalse(scheduler.stepOnceForTesting(now + 200_000_000L));
        assertFalse(scheduler.stepOnceForTesting(now + 300_000_000L));

        assertEquals(0, lowDone.get());
        assertEquals(3, scheduler.getDeferredAttempts(), "Should record 3 deferral attempts");
        assertEquals(1, scheduler.getUniqueDeferredTasks(), "Should count only 1 unique deferred task");
        assertEquals(1, scheduler.getCurrentlyDeferredCount());
    }

    @Test
    void testAgingPreventsStarvationUnderContinuousHighAndNormalFlood() {
        forceCriticalStress();
        scheduler.enableManualSteppingForTesting();

        AtomicBoolean lowExecuted = new AtomicBoolean(false);
        scheduler.submit(() -> lowExecuted.set(true), TaskPriority.LOW, "aged-low");

        long submitNano = System.nanoTime();
        // Flood HIGH and NORMAL queue continuously
        for (int i = 0; i < 6; i++) {
            scheduler.submit(() -> {}, TaskPriority.HIGH, "flood-h-" + i);
            scheduler.submit(() -> {}, TaskPriority.NORMAL, "flood-n-" + i);
        }

        // Advance time past MAX_LOW_STARVATION_NANOS (4.0s) even while HIGH/NORMAL queues are NON-EMPTY!
        long agedTime = submitNano + 4_500_000_000L;
        assertTrue(scheduler.stepOnceForTesting(agedTime), "Aged LOW task must preempt continuous flood");
        assertTrue(lowExecuted.get(), "LOW task must execute despite non-empty HIGH and NORMAL queues");
    }

    @Test
    void testHighWorkerIsolationPreventsBlockingByLongLowTask() throws InterruptedException {
        scheduler.start();

        CountDownLatch lowEntered = new CountDownLatch(1);
        CountDownLatch releaseLow = new CountDownLatch(1);
        CountDownLatch highCompleted = new CountDownLatch(1);

        // Submit slow blocking LOW job onto bg worker
        scheduler.submit(() -> {
            lowEntered.countDown();
            try {
                releaseLow.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, TaskPriority.LOW, "slow-low");

        assertTrue(lowEntered.await(2, TimeUnit.SECONDS), "LOW task should start on bg worker");

        // Submit HIGH task while LOW is blocked -> dedicated HIGH worker must finish it immediately!
        SubmissionResult highRes = scheduler.submit(highCompleted::countDown, TaskPriority.HIGH, "urgent-high");
        assertEquals(SubmissionStatus.ACCEPTED, highRes.status());
        assertTrue(highCompleted.await(2, TimeUnit.SECONDS), "HIGH task must execute immediately without waiting for slow LOW");

        releaseLow.countDown();
    }

    @Test
    void testQueueOverflowRejectsWithoutEvictingAcceptedHighTasks() {
        scheduler.enableManualSteppingForTesting();

        List<TaskHandle> acceptedHighs = new ArrayList<>();
        for (int i = 0; i < AdaptiveTaskScheduler.MAX_TIER_QUEUE_SIZE; i++) {
            SubmissionResult res = scheduler.submit(() -> {}, TaskPriority.HIGH, "h-" + i);
            assertEquals(SubmissionStatus.ACCEPTED, res.status());
            acceptedHighs.add(res.handle());
        }

        // 129th HIGH submission MUST be explicitly REJECTED and NEVER cancel/evict earlier accepted HIGH!
        SubmissionResult overflowHigh = scheduler.submit(() -> {}, TaskPriority.HIGH, "overflow-h");
        assertEquals(SubmissionStatus.REJECTED, overflowHigh.status());
        assertEquals(TaskState.REJECTED, overflowHigh.handle().getState());
        for (TaskHandle existing : acceptedHighs) {
            assertEquals(TaskState.PENDING, existing.getState(), "Already accepted HIGH task must never be evicted");
        }

        // Check NORMAL & LOW bounded rejection as well
        for (int i = 0; i < AdaptiveTaskScheduler.MAX_TIER_QUEUE_SIZE; i++) {
            assertEquals(SubmissionStatus.ACCEPTED, scheduler.submit(() -> {}, TaskPriority.NORMAL, "n-" + i).status());
            assertEquals(SubmissionStatus.ACCEPTED, scheduler.submit(() -> {}, TaskPriority.LOW, "l-" + i).status());
        }
        assertEquals(SubmissionStatus.REJECTED, scheduler.submit(() -> {}, TaskPriority.NORMAL, "overflow-n").status());
        assertEquals(SubmissionStatus.REJECTED, scheduler.submit(() -> {}, TaskPriority.LOW, "overflow-l").status());
        assertTrue(scheduler.getRejectedCount() >= 3);
    }

    @Test
    void testCoalescingDeduplicatesSameTagTasks() {
        scheduler.enableManualSteppingForTesting();

        SubmissionResult r1 = scheduler.submitCoalescing(() -> {}, TaskPriority.NORMAL, "snapshot-refresh");
        SubmissionResult r2 = scheduler.submitCoalescing(() -> {}, TaskPriority.NORMAL, "snapshot-refresh");

        assertEquals(SubmissionStatus.ACCEPTED, r1.status());
        assertEquals(SubmissionStatus.COALESCED, r2.status());
        assertSame(r1.handle(), r2.handle());
        assertEquals(1, scheduler.getPendingCount(TaskPriority.NORMAL));
        assertEquals(1, scheduler.getCoalescedCount());
    }

    @Test
    void testTaskExceptionCancellationAndCleanStopRestartWithoutDuplicateThreads() throws InterruptedException {
        scheduler.start();

        // Count active scheduler threads before work
        long initialCount = countSchedulerThreads();
        assertEquals(2, initialCount, "Must have exactly 2 bounded worker threads (High + Worker)");

        // Calling start() repeatedly MUST NOT create duplicate threads
        scheduler.start();
        assertEquals(2, countSchedulerThreads());

        // Task exception must not kill workers
        CountDownLatch survivorLatch = new CountDownLatch(1);
        scheduler.submit(() -> { throw new RuntimeException("Fault tolerance test"); }, TaskPriority.NORMAL, "fault");
        scheduler.submit(survivorLatch::countDown, TaskPriority.NORMAL, "survivor");
        assertTrue(survivorLatch.await(2, TimeUnit.SECONDS));

        // Cancel pending handle & tag cleanly
        CountDownLatch blockGate = new CountDownLatch(1);
        scheduler.submit(() -> {
            try { blockGate.await(1, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, TaskPriority.HIGH, "gate");

        TaskHandle cancelMe = scheduler.submit(() -> {}, TaskPriority.LOW, "stale-tag").handle();
        assertTrue(cancelMe.cancel());
        assertEquals(TaskState.CANCELLED, cancelMe.getState());
        assertTrue(cancelMe.isCancelled());
        blockGate.countDown();

        // Stop cancels remaining and rejects new submissions cleanly
        scheduler.stop();
        assertFalse(scheduler.isRunning());
        assertEquals(0, scheduler.getPendingCount());
        assertEquals(SubmissionStatus.REJECTED, scheduler.submit(() -> {}, TaskPriority.HIGH, "after-stop").status());

        // Restart cleanly produces exactly 2 workers, never 4
        scheduler.start();
        assertTrue(scheduler.isRunning());
        assertEquals(2, countSchedulerThreads(), "Restarting after stop must never duplicate scheduler threads");
        scheduler.stop();
    }

    private static long countSchedulerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.isAlive() && t.getName().startsWith("S1ind-Scheduler-"))
            .count();
    }
}
