package com.s1ind.opt.engine;

import com.s1ind.opt.S1indOptMod;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stress-aware background task dispatcher coupled to AdaptivePerformanceEngine.
 *
 * Isolates non-critical background jobs (telemetry maintenance, cache pruning,
 * memory diagnostics) and paces or defers low-priority work when APE detects
 * MODERATE or CRITICAL stress, protecting Vanilla's primary game and render threads.
 */
public final class AdaptiveTaskScheduler {

    public enum TaskPriority {
        HIGH(0),     // Always executes promptly
        NORMAL(1),   // Paced slightly during CRITICAL stress
        LOW(2);      // Deferred during CRITICAL stress

        final int weight;
        TaskPriority(int weight) { this.weight = weight; }
    }

    private record ScheduledTask(Runnable task, TaskPriority priority, long enqueuedNano)
        implements Comparable<ScheduledTask> {
        @Override
        public int compareTo(ScheduledTask other) {
            int p = Integer.compare(this.priority.weight, other.priority.weight);
            return p != 0 ? p : Long.compare(this.enqueuedNano, other.enqueuedNano);
        }
    }

    private static final AdaptiveTaskScheduler INSTANCE = new AdaptiveTaskScheduler();

    public static AdaptiveTaskScheduler getInstance() {
        return INSTANCE;
    }

    private final PriorityBlockingQueue<ScheduledTask> queue = new PriorityBlockingQueue<>(64, Comparator.naturalOrder());
    private final AtomicInteger deferredCount = new AtomicInteger(0);
    private volatile boolean running = false;
    private Thread workerThread = null;

    private AdaptiveTaskScheduler() {}

    public synchronized void start() {
        if (running) return;
        running = true;
        workerThread = new Thread(this::runLoop, "S1ind-Adaptive-Dispatcher");
        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.NORM_PRIORITY - 1); // Priority 4
        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        queue.clear();
    }

    public void submit(Runnable task, TaskPriority priority) {
        if (task == null) return;
        if (!running && !Thread.currentThread().isInterrupted()) {
            // Allow graceful direct check in tests or pre-start phases
            queue.offer(new ScheduledTask(task, priority, System.nanoTime()));
            return;
        }
        queue.offer(new ScheduledTask(task, priority, System.nanoTime()));
    }

    private void runLoop() {
        while (running) {
            try {
                ScheduledTask next = queue.take();
                processSingleTask(next);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                S1indOptMod.LOG.warn("[S1ind] AdaptiveTaskScheduler caught exception during dispatch", e);
            }
        }
    }

    /**
     * Executes one task respecting current APE StressLevel pacing rules.
     * Package-private helper exposed for deterministic unit testing.
     */
    boolean processSingleTask(ScheduledTask next) throws InterruptedException {
        StressLevel stress = AdaptivePerformanceEngine.getInstance().getStressLevel();

        if (next.priority() == TaskPriority.LOW && stress == StressLevel.CRITICAL) {
            long ageMs = (System.nanoTime() - next.enqueuedNano()) / 1_000_000L;
            if (ageMs < 5_000L) {
                // Defer low-priority maintenance while critical stress persists (max 5s deferral)
                deferredCount.incrementAndGet();
                queue.offer(next);
                Thread.sleep(250L);
                return false;
            }
        } else if (stress == StressLevel.MODERATE && next.priority() != TaskPriority.HIGH) {
            Thread.sleep(25L); // Gentle pacing to yield timeslices to main thread
        }

        next.task().run();
        return true;
    }

    public int getPendingCount() {
        return queue.size();
    }

    public int getDeferredCount() {
        return deferredCount.get();
    }

    public void resetForTesting() {
        queue.clear();
        deferredCount.set(0);
    }
}
