package com.s1ind.opt.engine;

import java.util.Arrays;

/**
 * Adaptive Performance Engine (APE) v3.1.0.
 *
 * Key Architecture Guarantees:
 * - 20,000-frame rolling ring buffer for statistically sound 1% Low (200 slowest) and 0.1% Low (20 slowest).
 * - Zero Render Thread sorting overhead: percentile computation runs on an isolated priority-1 background daemon.
 * - Anti-flapping hysteresis & dwell timer: requires sustained load shifts (3s escalate / 5s recover).
 * - Bottleneck attribution: differentiates CPU tick starvation, Render/GPU bottlenecks, and GC pauses.
 */
public final class AdaptivePerformanceEngine {

    private static final AdaptivePerformanceEngine INSTANCE = new AdaptivePerformanceEngine();

    public static AdaptivePerformanceEngine getInstance() {
        return INSTANCE;
    }

    // ── Frame Time Ring Buffer (20,000 frames) ────────────────────
    public static final int FRAME_BUFFER_SIZE = 20_000;
    private final long[] frameDurationsNano = new long[FRAME_BUFFER_SIZE];
    private final Object frameLock = new Object();
    private int frameIndex = 0;
    private int frameCount = 0;

    // ── Tick Metrics (128 ticks ring buffer) ──────────────────────
    private static final int TICK_BUFFER_SIZE = 128;
    private final long[] tickDurationsNano = new long[TICK_BUFFER_SIZE];
    private final Object tickLock = new Object();
    private int tickIndex = 0;
    private int tickCount = 0;
    private volatile boolean localServerActive = false;

    // ── Live Telemetry Metrics ────────────────────────────────────
    private volatile double cachedAvgFps = 60.0;
    private volatile double cachedAvgFrameTimeMs = 16.67;
    private volatile double cachedOnePercentLow = 60.0;
    private volatile double cachedPointOnePercentLow = 60.0;
    private volatile double cachedMspt = 0.0;
    private volatile StressLevel currentStress = StressLevel.NORMAL;
    private volatile BottleneckType bottleneckType = BottleneckType.BALANCED;

    // ── Hysteresis & Dwell Timer State ────────────────────────────
    private static final long ESCALATION_DWELL_MS = 3_000L;
    private static final long RECOVERY_DWELL_MS = 5_000L;
    private StressLevel candidateStress = StressLevel.NORMAL;
    private long candidateSinceMs = System.currentTimeMillis();

    // ── Background Telemetry Worker ───────────────────────────────
    private volatile boolean workerRunning = false;
    private Thread workerThread = null;

    private AdaptivePerformanceEngine() {
        Arrays.fill(frameDurationsNano, 16_666_666L);
    }

    public synchronized void startBackgroundWorker() {
        GcMonitor.register();
        if (workerRunning) {
            return;
        }
        workerRunning = true;
        workerThread = new Thread(this::workerLoop, "S1ind-Telemetry-Worker");
        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.MIN_PRIORITY);
        workerThread.start();
    }

    public synchronized void stopBackgroundWorker() {
        workerRunning = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    /**
     * Records a client frame duration in nanoseconds in O(1) time.
     * Performs zero allocations and zero sorting on the Render Thread.
     */
    public void recordFrame(long durationNano) {
        if (durationNano <= 0) return;
        synchronized (frameLock) {
            frameDurationsNano[frameIndex] = durationNano;
            frameIndex = (frameIndex + 1) % FRAME_BUFFER_SIZE;
            if (frameCount < FRAME_BUFFER_SIZE) {
                frameCount++;
            }
        }
    }

    /**
     * Records an integrated server tick duration in nanoseconds.
     */
    public void recordTick(long durationNano) {
        if (durationNano <= 0) return;
        this.localServerActive = true;
        synchronized (tickLock) {
            tickDurationsNano[tickIndex] = durationNano;
            tickIndex = (tickIndex + 1) % TICK_BUFFER_SIZE;
            if (tickCount < TICK_BUFFER_SIZE) {
                tickCount++;
            }
        }
    }

    public void setLocalServerActive(boolean active) {
        this.localServerActive = active;
    }

    public boolean isLocalServerActive() {
        return localServerActive;
    }

    private void workerLoop() {
        while (workerRunning) {
            try {
                Thread.sleep(1000L);
                recalculateNow(System.currentTimeMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Package-private / public synchronous trigger for unit testing and background loop.
     */
    public void recalculateNow(long nowMs) {
        // 1. Copy frame buffer outside Render Thread lock
        long[] sample;
        int count;
        synchronized (frameLock) {
            count = frameCount;
            if (count > 0) {
                sample = new long[count];
                System.arraycopy(frameDurationsNano, 0, sample, 0, count);
            } else {
                sample = null;
            }
        }

        if (sample != null && count > 0) {
            Arrays.sort(sample);

            long totalNano = 0;
            for (long d : sample) {
                totalNano += d;
            }
            double meanNano = (double) totalNano / count;
            this.cachedAvgFrameTimeMs = meanNano / 1_000_000.0;
            this.cachedAvgFps = meanNano > 0 ? (1_000_000_000.0 / meanNano) : 0.0;

            // 1% Low (99th percentile slowest frame -> average of slowest 1% or p99 cutoff)
            int idx99 = Math.min(count - 1, (int) Math.floor(count * 0.99));
            double p99Nano = sample[idx99];
            this.cachedOnePercentLow = p99Nano > 0 ? (1_000_000_000.0 / p99Nano) : 0.0;

            // 0.1% Low (99.9th percentile slowest frame)
            int idx999 = Math.min(count - 1, (int) Math.floor(count * 0.999));
            double p999Nano = sample[idx999];
            this.cachedPointOnePercentLow = p999Nano > 0 ? (1_000_000_000.0 / p999Nano) : 0.0;
        }

        // 2. Compute MSPT if running local server
        if (localServerActive) {
            long totalTickNano = 0;
            int tCount;
            synchronized (tickLock) {
                tCount = tickCount;
                for (int i = 0; i < tCount; i++) {
                    totalTickNano += tickDurationsNano[i];
                }
            }
            if (tCount > 0) {
                this.cachedMspt = (double) totalTickNano / (tCount * 1_000_000.0);
            }
        } else {
            this.cachedMspt = 0.0;
        }

        // 3. Evaluate raw target stress with hysteresis bands
        StressLevel rawTarget = evaluateRawStress(this.currentStress);
        updateStressWithHysteresis(rawTarget, nowMs);

        // 4. Identify Bottleneck
        this.bottleneckType = evaluateBottleneck();
    }

    private StressLevel evaluateRawStress(StressLevel current) {
        double fps = this.cachedAvgFps;
        double low1 = this.cachedOnePercentLow;
        double mspt = this.localServerActive ? this.cachedMspt : 0.0;

        // Hysteresis thresholds: require deeper recovery than entry threshold
        return switch (current) {
            case NORMAL -> {
                if (fps < 32.0 || low1 < 22.0 || mspt > 46.0) yield StressLevel.CRITICAL;
                if (fps < 48.0 || low1 < 35.0 || mspt > 36.0) yield StressLevel.MODERATE;
                yield StressLevel.NORMAL;
            }
            case MODERATE -> {
                if (fps < 32.0 || low1 < 22.0 || mspt > 46.0) yield StressLevel.CRITICAL;
                if (fps >= 55.0 && low1 >= 42.0 && mspt <= 30.0) yield StressLevel.NORMAL;
                yield StressLevel.MODERATE;
            }
            case CRITICAL -> {
                if (fps >= 42.0 && low1 >= 30.0 && mspt <= 40.0) yield StressLevel.MODERATE;
                yield StressLevel.CRITICAL;
            }
        };
    }

    private void updateStressWithHysteresis(StressLevel rawTarget, long nowMs) {
        if (rawTarget == this.currentStress) {
            this.candidateStress = this.currentStress;
            this.candidateSinceMs = nowMs;
            return;
        }

        if (rawTarget != this.candidateStress) {
            this.candidateStress = rawTarget;
            this.candidateSinceMs = nowMs;
            return;
        }

        boolean isEscalating = rawTarget.ordinal() > this.currentStress.ordinal();
        long requiredDwell = isEscalating ? ESCALATION_DWELL_MS : RECOVERY_DWELL_MS;

        if (nowMs - this.candidateSinceMs >= requiredDwell) {
            this.currentStress = rawTarget;
            this.candidateSinceMs = nowMs;
        }
    }

    private BottleneckType evaluateBottleneck() {
        if (GcMonitor.isRecentlyGcStalled()) {
            return BottleneckType.GC_BOUND;
        }
        if (localServerActive && cachedMspt > 38.0) {
            return BottleneckType.CPU_TICK_BOUND;
        }
        if (cachedAvgFrameTimeMs > 22.0 && (!localServerActive || cachedMspt < 28.0)) {
            return BottleneckType.RENDER_GPU_BOUND;
        }
        return BottleneckType.BALANCED;
    }

    /** Used in unit tests to reset internal buffers cleanly. */
    public void resetForTesting() {
        synchronized (frameLock) {
            frameIndex = 0;
            frameCount = 0;
            Arrays.fill(frameDurationsNano, 16_666_666L);
        }
        synchronized (tickLock) {
            tickIndex = 0;
            tickCount = 0;
            Arrays.fill(tickDurationsNano, 0L);
        }
        localServerActive = false;
        currentStress = StressLevel.NORMAL;
        candidateStress = StressLevel.NORMAL;
        candidateSinceMs = System.currentTimeMillis();
        cachedAvgFps = 60.0;
        cachedAvgFrameTimeMs = 16.67;
        cachedOnePercentLow = 60.0;
        cachedPointOnePercentLow = 60.0;
        cachedMspt = 0.0;
        bottleneckType = BottleneckType.BALANCED;
    }

    public int getSampledFrameCount() {
        synchronized (frameLock) {
            return frameCount;
        }
    }

    public double getAvgFps()                  { return cachedAvgFps; }
    public double getAvgFrameTimeMs()          { return cachedAvgFrameTimeMs; }
    public double getOnePercentLow()           { return cachedOnePercentLow; }
    public double getPointOnePercentLow()      { return cachedPointOnePercentLow; }
    public double getMspt()                    { return cachedMspt; }
    public StressLevel getStressLevel()        { return currentStress; }
    public BottleneckType getBottleneckType()  { return bottleneckType; }
}
