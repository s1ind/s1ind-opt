package com.s1ind.opt.engine;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;

/**
 * Adaptive Performance Engine (APE) - Core controller for S1ind Optimizer.
 *
 * Dynamically measures:
 * - Real-time Frame Times, Average FPS, 1% Low FPS, 0.1% Low FPS
 * - Milliseconds Per Tick (MSPT)
 * - GC Pauses (via GarbageCollectorMXBean)
 * - Heap allocation velocity (MB/s)
 *
 * Computes runtime StressLevel and informs sub-modules to scale background overhead.
 */
public final class AdaptivePerformanceEngine {

    private static final AdaptivePerformanceEngine INSTANCE = new AdaptivePerformanceEngine();

    public static AdaptivePerformanceEngine getInstance() {
        return INSTANCE;
    }

    // ── Frame Time Metrics (500 frames ring buffer) ───────────────
    private static final int FRAME_BUFFER_SIZE = 512;
    private final long[] frameDurationsNano = new long[FRAME_BUFFER_SIZE];
    private int frameIndex = 0;
    private int frameCount = 0;

    // ── Tick Metrics (100 ticks ring buffer) ───────────────────────
    private static final int TICK_BUFFER_SIZE = 128;
    private final long[] tickDurationsNano = new long[TICK_BUFFER_SIZE];
    private int tickIndex = 0;
    private int tickCount = 0;

    // ── Telemetry Cache ───────────────────────────────────────────
    private volatile double cachedAvgFps = 60.0;
    private volatile double cachedOnePercentLow = 60.0;
    private volatile double cachedPointOnePercentLow = 60.0;
    private volatile double cachedMspt = 20.0;
    private volatile StressLevel currentStress = StressLevel.NORMAL;

    // ── Memory & GC Tracking ──────────────────────────────────────
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private long lastAllocCheckTime = System.currentTimeMillis();
    private long lastUsedMemory = 0;
    private volatile double allocationRateMbPerSec = 0.0;
    private volatile long totalGcPauseMs = 0;

    private long lastStatsUpdate = 0;

    private AdaptivePerformanceEngine() {
        Arrays.fill(frameDurationsNano, 16_666_666L); // 60 FPS default
    }

    /**
     * Records a single client frame duration in nanoseconds.
     */
    public synchronized void recordFrame(long durationNano) {
        if (durationNano <= 0) return;

        frameDurationsNano[frameIndex] = durationNano;
        frameIndex = (frameIndex + 1) % FRAME_BUFFER_SIZE;
        if (frameCount < FRAME_BUFFER_SIZE) {
            frameCount++;
        }

        long now = System.currentTimeMillis();
        if (now - lastStatsUpdate >= 250) { // Refresh stats 4 times per second
            recalculateMetrics(now);
            lastStatsUpdate = now;
        }
    }

    /**
     * Records an integrated server tick duration in nanoseconds.
     */
    public synchronized void recordTick(long durationNano) {
        if (durationNano <= 0) return;

        tickDurationsNano[tickIndex] = durationNano;
        tickIndex = (tickIndex + 1) % TICK_BUFFER_SIZE;
        if (tickCount < TICK_BUFFER_SIZE) {
            tickCount++;
        }
    }

    private void recalculateMetrics(long nowMs) {
        // 1. Frame percentiles
        int count = frameCount;
        if (count > 0) {
            long[] copy = new long[count];
            System.arraycopy(frameDurationsNano, 0, copy, 0, count);
            Arrays.sort(copy);

            long totalNano = 0;
            for (long d : copy) totalNano += d;
            double meanFrameNano = (double) totalNano / count;
            this.cachedAvgFps = meanFrameNano > 0 ? (1_000_000_000.0 / meanFrameNano) : 0.0;

            // 1% Low = 99th percentile slowest frame
            int idx99 = Math.min(count - 1, (int) Math.round(count * 0.99));
            long p99Nano = copy[idx99];
            this.cachedOnePercentLow = p99Nano > 0 ? (1_000_000_000.0 / p99Nano) : 0.0;

            // 0.1% Low = 99.9th percentile slowest frame
            int idx999 = Math.min(count - 1, (int) Math.round(count * 0.999));
            long p999Nano = copy[idx999];
            this.cachedPointOnePercentLow = p999Nano > 0 ? (1_000_000_000.0 / p999Nano) : 0.0;
        }

        // 2. MSPT
        int tCount = tickCount;
        if (tCount > 0) {
            long totalTickNano = 0;
            for (int i = 0; i < tCount; i++) {
                totalTickNano += tickDurationsNano[i];
            }
            this.cachedMspt = (double) totalTickNano / (tCount * 1_000_000.0);
        }

        // 3. GC Duration
        long gcTime = 0;
        for (GarbageCollectorMXBean bean : gcBeans) {
            long t = bean.getCollectionTime();
            if (t > 0) gcTime += t;
        }
        this.totalGcPauseMs = gcTime;

        // 4. Allocation Rate (MB/sec)
        long elapsedAllocMs = nowMs - lastAllocCheckTime;
        if (elapsedAllocMs >= 1000) {
            Runtime rt = Runtime.getRuntime();
            long currentUsed = rt.totalMemory() - rt.freeMemory();
            if (currentUsed > lastUsedMemory && lastUsedMemory > 0) {
                double allocatedBytes = currentUsed - lastUsedMemory;
                this.allocationRateMbPerSec = (allocatedBytes / (1024.0 * 1024.0)) / (elapsedAllocMs / 1000.0);
            }
            this.lastUsedMemory = currentUsed;
            this.lastAllocCheckTime = nowMs;
        }

        // 5. Compute Stress Level
        // If FPS < 35 or MSPT > 45ms -> CRITICAL
        // If FPS < 50 or MSPT > 35ms -> MODERATE
        // Else -> NORMAL
        if (this.cachedAvgFps < 35.0 || this.cachedOnePercentLow < 25.0 || this.cachedMspt > 45.0) {
            this.currentStress = StressLevel.CRITICAL;
        } else if (this.cachedAvgFps < 52.0 || this.cachedOnePercentLow < 40.0 || this.cachedMspt > 35.0) {
            this.currentStress = StressLevel.MODERATE;
        } else {
            this.currentStress = StressLevel.NORMAL;
        }
    }

    public double getAvgFps() {
        return cachedAvgFps;
    }

    public double getOnePercentLow() {
        return cachedOnePercentLow;
    }

    public double getPointOnePercentLow() {
        return cachedPointOnePercentLow;
    }

    public double getMspt() {
        return cachedMspt;
    }

    public double getAllocationRateMbPerSec() {
        return allocationRateMbPerSec;
    }

    public long getTotalGcPauseMs() {
        return totalGcPauseMs;
    }

    public StressLevel getStressLevel() {
        return currentStress;
    }
}
