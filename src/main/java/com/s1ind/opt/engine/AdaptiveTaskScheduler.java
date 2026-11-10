package com.s1ind.opt.engine;

import com.s1ind.opt.S1indOptMod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Isolated two-worker reactive scheduler coupled to AdaptivePerformanceEngine.
 *
 * Architectural guarantees:
 * - Zero Thread.sleep() queue orchestration; uses ReentrantLock + Condition signaling.
 * - Dedicated HIGH worker thread guarantees long LOW/NORMAL jobs never block HIGH dispatch.
 * - Starvation prevention (aging) independently checks LOW/NORMAL queue head wait age even under
 *   continuous HIGH/NORMAL flood.
 * - Preserves already-accepted HIGH tasks on queue overflow (returns REJECTED instead of evicting).
 * - Separates deferredAttempts, uniqueDeferredTasks, currentlyDeferred, coalesced, and rejected counts.
 * - Verified lifecycle shutdown: waits on worker threads and refuses clearing live thread references early.
 */
public final class AdaptiveTaskScheduler {

    public enum TaskPriority {
        HIGH,
        NORMAL,
        LOW
    }

    public enum SubmissionStatus {
        ACCEPTED,
        COALESCED,
        REJECTED
    }

    public enum TaskState {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED,
        REJECTED
    }

    public enum LifecycleState {
        STOPPED,
        RUNNING,
        STOPPING
    }

    public static final class TaskHandle {
        private final AtomicReference<TaskState> state;
        private final AtomicBoolean cooperativeCancelRequested = new AtomicBoolean(false);
        private final AtomicLong waitTimeNanos = new AtomicLong(0L);
        private final AtomicLong executionTimeNanos = new AtomicLong(0L);

        TaskHandle(TaskState initialState) {
            this.state = new AtomicReference<>(initialState);
        }

        /**
         * Requests cancellation. Pending tasks transition to CANCELLED and will never execute.
         * Running tasks retain RUNNING until completion, while cooperativeCancelRequested becomes true.
         */
        public boolean cancel() {
            cooperativeCancelRequested.set(true);
            return state.compareAndSet(TaskState.PENDING, TaskState.CANCELLED);
        }

        public boolean isCancelled() {
            return cooperativeCancelRequested.get() || state.get() == TaskState.CANCELLED;
        }

        public TaskState getState()             { return state.get(); }
        public long getWaitTimeNanos()          { return waitTimeNanos.get(); }
        public long getExecutionTimeNanos()     { return executionTimeNanos.get(); }
    }

    public record SubmissionResult(SubmissionStatus status, TaskHandle handle) {
        public boolean isAcceptedOrCoalesced() {
            return status == SubmissionStatus.ACCEPTED || status == SubmissionStatus.COALESCED;
        }
    }

    private static final class QueuedTask {
        final Runnable runnable;
        final TaskPriority priority;
        final String tag;
        final long enqueuedNano;
        final TaskHandle handle;
        final AtomicBoolean markedDeferred = new AtomicBoolean(false);

        QueuedTask(Runnable runnable, TaskPriority priority, String tag, long enqueuedNano, TaskHandle handle) {
            this.runnable = runnable;
            this.priority = priority;
            this.tag = tag;
            this.enqueuedNano = enqueuedNano;
            this.handle = handle;
        }
    }

    public static final int MAX_TIER_QUEUE_SIZE = 128;
    private static final long LOW_DEFER_CRITICAL_WAIT_NANOS = 200_000_000L; // 200ms wakeup interval
    private static final long MODERATE_LOW_PACING_NANOS = 80_000_000L;      // 80ms pacing under MODERATE
    private static final long MAX_NORMAL_STARVATION_NANOS = 1_500_000_000L; // 1.5s NORMAL anti-starvation
    private static final long MAX_LOW_STARVATION_NANOS = 4_000_000_000L;    // 4.0s LOW anti-starvation
    private static final int FAIRNESS_BURST_LIMIT = 4;                      // Max consecutive high/normal before aged check
    private static final long SHUTDOWN_WAIT_MS = 1_500L;

    private static final AdaptiveTaskScheduler INSTANCE = new AdaptiveTaskScheduler();

    public static AdaptiveTaskScheduler getInstance() {
        return INSTANCE;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition highWorkAvailable = lock.newCondition();
    private final Condition bgWorkAvailable = lock.newCondition();

    private final Deque<QueuedTask> highQueue = new ArrayDeque<>(MAX_TIER_QUEUE_SIZE);
    private final Deque<QueuedTask> normalQueue = new ArrayDeque<>(MAX_TIER_QUEUE_SIZE);
    private final Deque<QueuedTask> lowQueue = new ArrayDeque<>(MAX_TIER_QUEUE_SIZE);

    private final AtomicInteger deferredAttempts = new AtomicInteger(0);
    private final AtomicInteger uniqueDeferredTasks = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final AtomicInteger coalescedCount = new AtomicInteger(0);
    private final AtomicInteger executedCount = new AtomicInteger(0);
    private final AtomicInteger overflowWarnCounter = new AtomicInteger(0);

    private volatile LifecycleState lifecycle = LifecycleState.STOPPED;
    private volatile boolean acceptingSubmissions = false;
    private volatile long nextLowAllowedNano = 0L;
    private int consecutiveBgBurst = 0;

    private Thread highWorker = null;
    private Thread bgWorker = null;

    private AdaptiveTaskScheduler() {}

    public synchronized void start() {
        if (lifecycle == LifecycleState.RUNNING) {
            return;
        }
        // Refuse creating duplicate workers if old threads haven't finished terminating yet
        if ((highWorker != null && highWorker.isAlive()) || (bgWorker != null && bgWorker.isAlive())) {
            awaitExistingWorkersTermination();
            if ((highWorker != null && highWorker.isAlive()) || (bgWorker != null && bgWorker.isAlive())) {
                S1indOptMod.LOG.warn("[S1ind] Previous scheduler workers still terminating; skipping duplicate start.");
                return;
            }
        }

        lifecycle = LifecycleState.RUNNING;
        acceptingSubmissions = true;
        consecutiveBgBurst = 0;

        highWorker = new Thread(this::highWorkerLoop, "S1ind-Scheduler-High");
        highWorker.setDaemon(true);
        highWorker.setPriority(Thread.NORM_PRIORITY);

        bgWorker = new Thread(this::bgWorkerLoop, "S1ind-Scheduler-Worker");
        bgWorker.setDaemon(true);
        bgWorker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));

        highWorker.start();
        bgWorker.start();
    }

    /**
     * Enables submission without starting background threads (strictly for deterministic unit tests).
     */
    synchronized void enableManualSteppingForTesting() {
        lifecycle = LifecycleState.STOPPED;
        acceptingSubmissions = true;
        consecutiveBgBurst = 0;
    }

    public synchronized void stop() {
        if (lifecycle == LifecycleState.STOPPED && highWorker == null && bgWorker == null) {
            acceptingSubmissions = false;
            return;
        }

        lifecycle = LifecycleState.STOPPING;
        acceptingSubmissions = false;

        lock.lock();
        try {
            cancelAllPending(highQueue);
            cancelAllPending(normalQueue);
            cancelAllPending(lowQueue);
            highWorkAvailable.signalAll();
            bgWorkAvailable.signalAll();
        } finally {
            lock.unlock();
        }

        if (highWorker != null) highWorker.interrupt();
        if (bgWorker != null) bgWorker.interrupt();

        awaitExistingWorkersTermination();

        boolean highStopped = (highWorker == null || !highWorker.isAlive());
        boolean bgStopped = (bgWorker == null || !bgWorker.isAlive());

        if (highStopped) highWorker = null;
        if (bgStopped) bgWorker = null;

        if (highStopped && bgStopped) {
            lifecycle = LifecycleState.STOPPED;
        } else {
            S1indOptMod.LOG.warn("[S1ind] Scheduler worker failed to terminate cleanly within {}ms timeout", SHUTDOWN_WAIT_MS);
        }
    }

    public void shutdown() {
        stop();
    }

    private void awaitExistingWorkersTermination() {
        long deadlineMs = System.currentTimeMillis() + SHUTDOWN_WAIT_MS;
        joinWorkerUntil(highWorker, deadlineMs);
        joinWorkerUntil(bgWorker, deadlineMs);
    }

    private static void joinWorkerUntil(Thread thread, long deadlineMs) {
        if (thread == null) return;
        long remaining = deadlineMs - System.currentTimeMillis();
        if (remaining > 0) {
            try {
                thread.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public SubmissionResult submit(Runnable runnable, TaskPriority priority) {
        return submitInternal(runnable, priority, "default", false);
    }

    public SubmissionResult submit(Runnable runnable, TaskPriority priority, String tag) {
        return submitInternal(runnable, priority, tag, false);
    }

    public SubmissionResult submitCoalescing(Runnable runnable, TaskPriority priority, String tag) {
        return submitInternal(runnable, priority, tag, true);
    }

    private SubmissionResult submitInternal(Runnable runnable, TaskPriority priority, String tag, boolean coalesce) {
        if (runnable == null || priority == null || !acceptingSubmissions) {
            if (!acceptingSubmissions && runnable != null) {
                rejectedCount.incrementAndGet();
            }
            return new SubmissionResult(SubmissionStatus.REJECTED, new TaskHandle(TaskState.REJECTED));
        }

        String safeTag = tag != null ? tag : "default";
        long nowNano = System.nanoTime();

        lock.lock();
        try {
            if (!acceptingSubmissions) {
                rejectedCount.incrementAndGet();
                return new SubmissionResult(SubmissionStatus.REJECTED, new TaskHandle(TaskState.REJECTED));
            }

            Deque<QueuedTask> targetQueue = queueFor(priority);
            purgeCancelled(targetQueue);

            if (coalesce) {
                for (QueuedTask existing : targetQueue) {
                    if (safeTag.equals(existing.tag) && existing.handle.getState() == TaskState.PENDING) {
                        coalescedCount.incrementAndGet();
                        return new SubmissionResult(SubmissionStatus.COALESCED, existing.handle);
                    }
                }
            }

            // Never evict already-accepted tasks (especially HIGH); explicitly reject new overflow
            if (targetQueue.size() >= MAX_TIER_QUEUE_SIZE) {
                rejectedCount.incrementAndGet();
                int count = overflowWarnCounter.incrementAndGet();
                if (count == 1 || (count & 31) == 0) {
                    S1indOptMod.LOG.warn("[S1ind] AdaptiveTaskScheduler {} queue full ({}/{}); rejected task '{}'",
                        priority, targetQueue.size(), MAX_TIER_QUEUE_SIZE, safeTag);
                }
                return new SubmissionResult(SubmissionStatus.REJECTED, new TaskHandle(TaskState.REJECTED));
            }

            TaskHandle handle = new TaskHandle(TaskState.PENDING);
            QueuedTask task = new QueuedTask(runnable, priority, safeTag, nowNano, handle);
            targetQueue.addLast(task);

            if (priority == TaskPriority.HIGH) {
                highWorkAvailable.signal();
            }
            bgWorkAvailable.signal();

            return new SubmissionResult(SubmissionStatus.ACCEPTED, handle);
        } finally {
            lock.unlock();
        }
    }

    public void cancelByTag(String tag) {
        if (tag == null) return;
        lock.lock();
        try {
            cancelTagInQueue(highQueue, tag);
            cancelTagInQueue(normalQueue, tag);
            cancelTagInQueue(lowQueue, tag);
        } finally {
            lock.unlock();
        }
    }

    private static void cancelTagInQueue(Deque<QueuedTask> queue, String tag) {
        for (QueuedTask t : queue) {
            if (tag.equals(t.tag)) {
                t.handle.cancel();
            }
        }
    }

    private static void cancelAllPending(Deque<QueuedTask> queue) {
        while (!queue.isEmpty()) {
            queue.removeFirst().handle.cancel();
        }
    }

    private Deque<QueuedTask> queueFor(TaskPriority priority) {
        return switch (priority) {
            case HIGH -> highQueue;
            case NORMAL -> normalQueue;
            case LOW -> lowQueue;
        };
    }

    private void highWorkerLoop() {
        while (lifecycle == LifecycleState.RUNNING) {
            QueuedTask toRun = null;
            lock.lock();
            try {
                while (lifecycle == LifecycleState.RUNNING) {
                    purgeCancelled(highQueue);
                    if (!highQueue.isEmpty()) {
                        toRun = highQueue.removeFirst();
                        break;
                    }
                    highWorkAvailable.await();
                }
            } catch (InterruptedException e) {
                if (lifecycle != LifecycleState.RUNNING) break;
            } finally {
                lock.unlock();
            }

            if (toRun != null) {
                executeSafely(toRun);
            }
        }
    }

    private void bgWorkerLoop() {
        while (lifecycle == LifecycleState.RUNNING) {
            QueuedTask toRun = null;
            lock.lock();
            try {
                while (lifecycle == LifecycleState.RUNNING) {
                    toRun = selectNextBgTaskLocked(System.nanoTime());
                    if (toRun != null) {
                        break;
                    }
                    if (!lowQueue.isEmpty()) {
                        bgWorkAvailable.await(LOW_DEFER_CRITICAL_WAIT_NANOS, TimeUnit.NANOSECONDS);
                    } else {
                        bgWorkAvailable.await();
                    }
                }
            } catch (InterruptedException e) {
                if (lifecycle != LifecycleState.RUNNING) break;
            } finally {
                lock.unlock();
            }

            if (toRun != null) {
                executeSafely(toRun);
            }
        }
    }

    /**
     * Fairness-enforced task selection across HIGH / NORMAL / LOW queues.
     *
     * Checks queue head age independently of whether higher-priority queues are empty,
     * preventing infinite starvation under continuous HIGH/NORMAL admission.
     */
    QueuedTask selectNextBgTaskLocked(long nowNano) {
        purgeCancelled(highQueue);
        purgeCancelled(normalQueue);
        purgeCancelled(lowQueue);

        QueuedTask lowHead = lowQueue.peekFirst();
        QueuedTask normalHead = normalQueue.peekFirst();
        StressLevel stress = AdaptivePerformanceEngine.getInstance().getStressLevel();

        // 1. Strict anti-starvation aging check BEFORE checking high/normal burst!
        if (lowHead != null) {
            long lowAge = nowNano - lowHead.enqueuedNano;
            boolean criticalExpired = lowAge >= MAX_LOW_STARVATION_NANOS;
            boolean normalExpired = stress != StressLevel.CRITICAL && lowAge >= (MAX_LOW_STARVATION_NANOS / 2);

            if (criticalExpired || (normalExpired && consecutiveBgBurst >= FAIRNESS_BURST_LIMIT)) {
                consecutiveBgBurst = 0;
                nextLowAllowedNano = nowNano + MODERATE_LOW_PACING_NANOS;
                return lowQueue.removeFirst();
            }
        }

        if (normalHead != null && (nowNano - normalHead.enqueuedNano) >= MAX_NORMAL_STARVATION_NANOS
            && consecutiveBgBurst >= FAIRNESS_BURST_LIMIT) {
            consecutiveBgBurst = 0;
            return normalQueue.removeFirst();
        }

        // 2. Assist HIGH backlog if present
        if (!highQueue.isEmpty()) {
            consecutiveBgBurst++;
            return highQueue.removeFirst();
        }

        // 3. NORMAL queue
        if (!normalQueue.isEmpty()) {
            consecutiveBgBurst++;
            return normalQueue.removeFirst();
        }

        // 4. LOW queue governed by APE StressLevel
        if (lowHead != null) {
            long lowAge = nowNano - lowHead.enqueuedNano;

            if (stress == StressLevel.NORMAL || lowAge >= MAX_LOW_STARVATION_NANOS) {
                consecutiveBgBurst = 0;
                nextLowAllowedNano = nowNano + MODERATE_LOW_PACING_NANOS;
                return lowQueue.removeFirst();
            }

            if (stress == StressLevel.MODERATE && nowNano >= nextLowAllowedNano) {
                consecutiveBgBurst = 0;
                nextLowAllowedNano = nowNano + MODERATE_LOW_PACING_NANOS;
                return lowQueue.removeFirst();
            }

            // Defer LOW cleanly without Thread.sleep
            recordDeferral(lowHead);
            return null;
        }

        return null;
    }

    private void recordDeferral(QueuedTask task) {
        deferredAttempts.incrementAndGet();
        if (task.markedDeferred.compareAndSet(false, true)) {
            uniqueDeferredTasks.incrementAndGet();
        }
    }

    private static void purgeCancelled(Deque<QueuedTask> queue) {
        while (!queue.isEmpty() && queue.peekFirst().handle.isCancelled()) {
            queue.removeFirst();
        }
    }

    private void executeSafely(QueuedTask task) {
        // Atomic CAS guard prevents double execution or executing after cancellation
        if (!task.handle.state.compareAndSet(TaskState.PENDING, TaskState.RUNNING)) {
            return;
        }
        if (task.handle.isCancelled()) {
            task.handle.state.set(TaskState.CANCELLED);
            return;
        }

        long startNano = System.nanoTime();
        task.handle.waitTimeNanos.set(Math.max(0L, startNano - task.enqueuedNano));

        try {
            task.runnable.run();
            if (task.handle.isCancelled()) {
                task.handle.state.compareAndSet(TaskState.RUNNING, TaskState.CANCELLED);
            } else {
                task.handle.state.compareAndSet(TaskState.RUNNING, TaskState.COMPLETED);
                executedCount.incrementAndGet();
            }
        } catch (Exception e) {
            task.handle.state.set(TaskState.FAILED);
            S1indOptMod.LOG.warn("[S1ind] Background task '{}' threw exception", task.tag, e);
        } finally {
            task.handle.executionTimeNanos.set(Math.max(0L, System.nanoTime() - startNano));
        }
    }

    /** Deterministic single-step helper for unit tests. */
    boolean stepOnceForTesting(long nowNano) {
        QueuedTask task;
        lock.lock();
        try {
            task = selectNextBgTaskLocked(nowNano);
        } finally {
            lock.unlock();
        }
        if (task != null) {
            executeSafely(task);
            return task.handle.getState() == TaskState.COMPLETED;
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

    public int getPendingCount(TaskPriority priority) {
        lock.lock();
        try {
            return queueFor(priority).size();
        } finally {
            lock.unlock();
        }
    }

    public int getCurrentlyDeferredCount() {
        if (AdaptivePerformanceEngine.getInstance().getStressLevel() == StressLevel.NORMAL) {
            return 0;
        }
        return getPendingCount(TaskPriority.LOW);
    }

    public int getDeferredAttempts()      { return deferredAttempts.get(); }
    public int getUniqueDeferredTasks()   { return uniqueDeferredTasks.get(); }
    public int getRejectedCount()         { return rejectedCount.get(); }
    public int getCoalescedCount()        { return coalescedCount.get(); }
    public int getExecutedCount()         { return executedCount.get(); }
    public LifecycleState getLifecycle()  { return lifecycle; }
    public boolean isRunning()            { return lifecycle == LifecycleState.RUNNING; }

    public synchronized void resetForTesting() {
        stop();
        lock.lock();
        try {
            highQueue.clear();
            normalQueue.clear();
            lowQueue.clear();
        } finally {
            lock.unlock();
        }
        deferredAttempts.set(0);
        uniqueDeferredTasks.set(0);
        rejectedCount.set(0);
        coalescedCount.set(0);
        executedCount.set(0);
        overflowWarnCounter.set(0);
        consecutiveBgBurst = 0;
        nextLowAllowedNano = 0L;
    }
}
