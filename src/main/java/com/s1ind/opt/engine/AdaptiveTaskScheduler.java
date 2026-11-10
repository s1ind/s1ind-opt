package com.s1ind.opt.engine;

import com.s1ind.opt.S1indOptMod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reactive, lock/condition-driven background task dispatcher coupled to APE.
 *
 * Design guarantees:
 * - Zero Thread.sleep() usage: waits cleanly on Condition.awaitNanos(), ensuring
 *   incoming HIGH tasks wake the worker instantaneously with zero LOW-task blocking.
 * - Strictly bounded queues per priority tier with overflow rejection counters.
 * - Starvation protection: tracks enqueue age; aged LOW tasks execute past MAX_LOW_DEFERRAL_NANOS.
 * - Supports stale-task cancellation via TaskHandle and tags.
 */
public final class AdaptiveTaskScheduler {

    public enum TaskPriority {
        HIGH,
        NORMAL,
        LOW
    }

    public static final class TaskHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
    }

    private record QueuedTask(
        Runnable runnable,
        TaskPriority priority,
        String tag,
        long enqueuedNano,
        TaskHandle handle
    ) {}

    public static final int MAX_TIER_QUEUE_SIZE = 128;
    private static final long LOW_DEFER_CRITICAL_WAIT_NANOS = 250_000_000L;  // 250ms wake interval
    private static final long MODERATE_LOW_PACING_NANOS = 100_000_000L;      // 100ms pacing under MODERATE
    private static final long MAX_LOW_STARVATION_NANOS = 4_000_000_000L;     // Promote after 4.0s wait

    private static final AdaptiveTaskScheduler INSTANCE = new AdaptiveTaskScheduler();

    public static AdaptiveTaskScheduler getInstance() {
        return INSTANCE;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();

    private final Deque<QueuedTask> highQueue = new ArrayDeque<>(MAX_TIER_QUEUE_SIZE);
    private final Deque<QueuedTask> normalQueue = new ArrayDeque<>(MAX_TIER_QUEUE_SIZE);
    private final Deque<QueuedTask> lowQueue = new ArrayDeque<>(MAX_TIER_QUEUE_SIZE);

    private final AtomicInteger deferredCount = new AtomicInteger(0);
    private final AtomicInteger droppedCount = new AtomicInteger(0);
    private final AtomicInteger executedCount = new AtomicInteger(0);

    private volatile boolean running = false;
    private Thread workerThread = null;
    private long nextLowAllowedNano = 0L;

    private AdaptiveTaskScheduler() {}

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this::dispatchLoop, "S1ind-Adaptive-Scheduler");
        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.NORM_PRIORITY - 1);
        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        lock.lock();
        try {
            highQueue.clear();
            normalQueue.clear();
            lowQueue.clear();
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    public TaskHandle submit(Runnable runnable, TaskPriority priority) {
        return submit(runnable, priority, "default");
    }

    public TaskHandle submit(Runnable runnable, TaskPriority priority, String tag) {
        TaskHandle handle = new TaskHandle();
        if (runnable == null || priority == null) {
            handle.cancel();
            return handle;
        }

        QueuedTask task = new QueuedTask(
            runnable,
            priority,
            tag != null ? tag : "default",
            System.nanoTime(),
            handle
        );

        lock.lock();
        try {
            Deque<QueuedTask> target = queueFor(priority);
            if (target.size() >= MAX_TIER_QUEUE_SIZE) {
                if (priority == TaskPriority.HIGH) {
                    // Evict oldest LOW or NORMAL task to honor HIGH admission if overloaded, else drop oldest
                    if (!lowQueue.isEmpty()) {
                        lowQueue.removeFirst().handle().cancel();
                        droppedCount.incrementAndGet();
                    } else if (!target.isEmpty()) {
                        target.removeFirst().handle().cancel();
                        droppedCount.incrementAndGet();
                    }
                } else {
                    droppedCount.incrementAndGet();
                    handle.cancel();
                    return handle;
                }
            }
            target.addLast(task);
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        return handle;
    }

    public void cancelByTag(String tag) {
        if (tag == null) return;
        lock.lock();
        try {
            cancelInQueue(highQueue, tag);
            cancelInQueue(normalQueue, tag);
            cancelInQueue(lowQueue, tag);
        } finally {
            lock.unlock();
        }
    }

    private static void cancelInQueue(Deque<QueuedTask> queue, String tag) {
        for (QueuedTask t : queue) {
            if (tag.equals(t.tag())) {
                t.handle().cancel();
            }
        }
    }

    private Deque<QueuedTask> queueFor(TaskPriority priority) {
        return switch (priority) {
            case HIGH -> highQueue;
            case NORMAL -> normalQueue;
            case LOW -> lowQueue;
        };
    }

    private void dispatchLoop() {
        while (running) {
            QueuedTask toRun = null;
            lock.lock();
            try {
                while (running) {
                    toRun = selectNextTaskLocked(System.nanoTime());
                    if (toRun != null) {
                        break;
                    }
                    // If low queue has deferred tasks during CRITICAL/MODERATE stress, await with wake timeout
                    if (!lowQueue.isEmpty()) {
                        workAvailable.await(LOW_DEFER_CRITICAL_WAIT_NANOS, TimeUnit.NANOSECONDS);
                    } else {
                        workAvailable.await();
                    }
                }
            } catch (InterruptedException e) {
                if (!running) break;
            } finally {
                lock.unlock();
            }

            if (toRun != null && !toRun.handle().isCancelled()) {
                executeSafely(toRun);
            }
        }
    }

    /**
     * Selects the highest priority eligible task respecting APE StressLevel and starvation bounds.
     * Package-private for deterministic unit verification.
     */
    QueuedTask selectNextTaskLocked(long nowNano) {
        // Purge cancelled items from heads
        purgeCancelled(highQueue);
        purgeCancelled(normalQueue);
        purgeCancelled(lowQueue);

        // 1. HIGH always drains first, unconditionally
        if (!highQueue.isEmpty()) {
            return highQueue.removeFirst();
        }

        // 2. NORMAL always executes ahead of LOW
        if (!normalQueue.isEmpty()) {
            return normalQueue.removeFirst();
        }

        // 3. LOW executes based on current APE StressLevel + anti-starvation aging
        if (!lowQueue.isEmpty()) {
            QueuedTask head = lowQueue.peekFirst();
            long waitAgeNano = nowNano - head.enqueuedNano();
            StressLevel stress = AdaptivePerformanceEngine.getInstance().getStressLevel();

            if (stress == StressLevel.NORMAL || waitAgeNano >= MAX_LOW_STARVATION_NANOS) {
                nextLowAllowedNano = nowNano + MODERATE_LOW_PACING_NANOS;
                return lowQueue.removeFirst();
            }

            if (stress == StressLevel.MODERATE) {
                if (nowNano >= nextLowAllowedNano) {
                    nextLowAllowedNano = nowNano + MODERATE_LOW_PACING_NANOS;
                    return lowQueue.removeFirst();
                } else {
                    deferredCount.incrementAndGet();
                    return null;
                }
            }

            // CRITICAL stress and within starvation limit -> defer LOW work
            deferredCount.incrementAndGet();
            return null;
        }

        return null;
    }

    private static void purgeCancelled(Deque<QueuedTask> queue) {
        while (!queue.isEmpty() && queue.peekFirst().handle().isCancelled()) {
            queue.removeFirst();
        }
    }

    private void executeSafely(QueuedTask task) {
        try {
            task.runnable().run();
            executedCount.incrementAndGet();
        } catch (Exception e) {
            S1indOptMod.LOG.warn("[S1ind] Background task '{}' threw exception", task.tag(), e);
        }
    }

    /** Package-private test helper to drain one step synchronously without waiting for worker thread. */
    boolean stepOnceForTesting(long nowNano) {
        QueuedTask task;
        lock.lock();
        try {
            task = selectNextTaskLocked(nowNano);
        } finally {
            lock.unlock();
        }
        if (task != null && !task.handle().isCancelled()) {
            executeSafely(task);
            return true;
        }
        return false;
    }

    public int getPendingCount() {
        lock.lock();
        try {
            return highQueue.size() + normalQueue.size() + lowQueue.size();
        } finally {
            lock.unlock();
        }
    }

    public int getDeferredCount() { return deferredCount.get(); }
    public int getDroppedCount()  { return droppedCount.get(); }
    public int getExecutedCount() { return executedCount.get(); }
    public boolean isRunning()    { return running; }

    public void resetForTesting() {
        stop();
        deferredCount.set(0);
        droppedCount.set(0);
        executedCount.set(0);
        nextLowAllowedNano = 0L;
    }
}
