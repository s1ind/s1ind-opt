package com.s1ind.opt.engine;

import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskHandle;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void testHighPriorityExecutesStrictlyBeforeNormalAndLow() {
        List<String> order = new ArrayList<>();
        scheduler.submit(() -> order.add("LOW"), TaskPriority.LOW);
        scheduler.submit(() -> order.add("NORMAL"), TaskPriority.NORMAL);
        scheduler.submit(() -> order.add("HIGH"), TaskPriority.HIGH);

        long now = System.nanoTime();
        while (scheduler.stepOnceForTesting(now)) {
            now += 200_000_000L;
        }

        assertEquals(List.of("HIGH", "NORMAL", "LOW"), order);
    }

    @Test
    void testLowDefersWhileHighContinuesUnderCriticalStress() {
        forceCriticalStress();

        AtomicInteger highDone = new AtomicInteger(0);
        AtomicInteger lowDone = new AtomicInteger(0);

        scheduler.submit(lowDone::incrementAndGet, TaskPriority.LOW);
        scheduler.submit(highDone::incrementAndGet, TaskPriority.HIGH);

        long now = System.nanoTime();
        // Step 1 executes HIGH immediately
        assertTrue(scheduler.stepOnceForTesting(now));
        assertEquals(1, highDone.get());
        assertEquals(0, lowDone.get());

        // Step 2 attempts LOW under CRITICAL -> defers immediately without sleeping!
        assertFalse(scheduler.stepOnceForTesting(now));
        assertEquals(0, lowDone.get());
        assertTrue(scheduler.getDeferredCount() > 0);
    }

    @Test
    void testLowTaskAntiStarvationExecutesAfterMaxDeferral() {
        forceCriticalStress();

        AtomicInteger lowDone = new AtomicInteger(0);
        scheduler.submit(lowDone::incrementAndGet, TaskPriority.LOW);

        long now = System.nanoTime();
        // Deferred while recent
        assertFalse(scheduler.stepOnceForTesting(now + 1_000_000_000L));
        assertEquals(0, lowDone.get());

        // Past 4.0s anti-starvation ceiling -> LOW executes even during CRITICAL!
        assertTrue(scheduler.stepOnceForTesting(now + 4_500_000_000L));
        assertEquals(1, lowDone.get());
    }

    @Test
    void testBoundedQueuePreventsUncontrolledGrowth() {
        for (int i = 0; i < 200; i++) {
            scheduler.submit(() -> {}, TaskPriority.LOW);
        }
        assertEquals(AdaptiveTaskScheduler.MAX_TIER_QUEUE_SIZE, scheduler.getPendingCount());
        assertTrue(scheduler.getDroppedCount() >= 200 - AdaptiveTaskScheduler.MAX_TIER_QUEUE_SIZE);
    }

    @Test
    void testTaskExceptionDoesNotKillSchedulerWorker() throws InterruptedException {
        scheduler.start();
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.submit(() -> { throw new RuntimeException("Intentional test fault"); }, TaskPriority.HIGH, "faulty");
        scheduler.submit(latch::countDown, TaskPriority.NORMAL, "survivor");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Scheduler must survive task exception and execute subsequent work");
        assertTrue(scheduler.isRunning());
    }

    @Test
    void testTaskAndTagCancellationAndCleanShutdown() {
        AtomicInteger executed = new AtomicInteger(0);
        TaskHandle h1 = scheduler.submit(executed::incrementAndGet, TaskPriority.HIGH, "chunk-stale");
        h1.cancel();
        scheduler.submit(executed::incrementAndGet, TaskPriority.NORMAL, "chunk-stale");
        scheduler.cancelByTag("chunk-stale");

        while (scheduler.stepOnceForTesting(System.nanoTime())) {}
        assertEquals(0, executed.get(), "Cancelled tasks must not execute");

        scheduler.start();
        assertTrue(scheduler.isRunning());
        scheduler.stop();
        assertFalse(scheduler.isRunning());
        assertEquals(0, scheduler.getPendingCount());
    }
}
