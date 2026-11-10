package com.s1ind.opt.engine;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.config.PerformanceProfile;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskPriority;

import java.util.Arrays;

/**
 * Adaptive Performance Engine (APE) v3.2.1.
 *
 * Architecture guarantees:
 * - Zero Render Thread allocation and O(1) ping-pong frame recording without sorting locks.
 * - Fixed 20,000-frame rolling window + pre-allocated sort workspace (0 B/s worker churn).
 * - True arithmetic mean of worst 1% (200 frames at full buffer) and worst 0.1% (20 frames).
 * - Explicit readiness lifecycle: INITIALIZING (<500), PARTIAL (500..19,999), STABLE (20,000).
 * - Decoupled client & dedicated server telemetry; server runs purely on MSPT when headless.
 * - Dynamic FPS Cap / VSync awareness without inventing arbitrary 60 FPS ceilings.
 * - Monotonic System.nanoTime() dwell & hysteresis preventing state flapping.
 */
public final class AdaptivePerformanceEngine {

    private static final AdaptivePerformanceEngine INSTANCE = new AdaptivePerformanceEngine();

    public static AdaptivePerformanceEngine getInstance() {
        return INSTANCE;
    }

    public static final int FRAME_BUFFER_SIZE = 20_000;
    public static final int WARMUP_MIN_FRAMES = 500;
    private static final int BATCH_CAPACITY = FRAME_BUFFER_SIZE;
    private static final int TICK_BUFFER_SIZE = 128;

    // ── Ping-Pong Frame Batch (Zero Render-Thread Lock Contention) ─
    private long[] activeFrameBatch = new long[BATCH_CAPACITY];
    private long[] drainFrameBatch = new long[BATCH_CAPACITY];
    private int activeBatchCount = 0;
    private final Object batchLock = new Object();

    // ── Worker-Owned Rolling History & Sort Workspace ──────────────
    private final long[] rollingFrames = new long[FRAME_BUFFER_SIZE];
    private final long[] sortWorkspace = new long[FRAME_BUFFER_SIZE];
    private int rollingHead = 0;
    private int rollingCount = 0;

    // ── Tick Metrics ──────────────────────────────────────────────
    private final long[] rollingTicks = new long[TICK_BUFFER_SIZE];
    private int tickHead = 0;
    private int tickCount = 0;
    private final Object tickLock = new Object();
    private volatile boolean serverTickRecorded = false;
    private volatile boolean clientFrameRecorded = false;
    private volatile boolean integratedServerActive = false;

    // ── Telemetry Metrics ─────────────────────────────────────────
    private volatile double cachedAvgFps = 0.0;
    private volatile double cachedAvgFrameTimeMs = 0.0;
    private volatile double cachedOnePercentLow = 0.0;
    private volatile double cachedPointOnePercentLow = 0.0;
    private volatile int cachedOnePercentSampleSize = 0;
    private volatile int cachedPointOnePercentSampleSize = 0;
    private volatile double cachedMspt = 0.0;
    private volatile int cachedSampleCount = 0;
    private volatile TelemetryReadiness readiness = TelemetryReadiness.INITIALIZING;

    private volatile int targetFpsCap = 260; // >250 treated as uncapped by Vanilla Video Settings
    private volatile boolean vsyncEnabled = false;
    private volatile StressLevel currentStress = StressLevel.NORMAL;
    private volatile BottleneckType bottleneckType = BottleneckType.UNKNOWN;
    private volatile ConfidenceLevel bottleneckConfidence = ConfidenceLevel.LOW;

    // ── Monotonic Hysteresis State ────────────────────────────────
    private static final long DEFAULT_ESCALATE_DWELL_NANOS = 3_000_000_000L;     // 3.0s
    private static final long PERFORMANCE_ESCALATE_DWELL_NANOS = 1_500_000_000L; // 1.5s
    private static final long RECOVERY_DWELL_NANOS = 5_000_000_000L;             // 5.0s
    private StressLevel candidateStress = StressLevel.NORMAL;
    private long candidateSinceNano = System.nanoTime();

    private volatile boolean workerRunning = false;
    private Thread workerThread = null;

    private AdaptivePerformanceEngine() {}

    public synchronized void startBackgroundWorker() {
        GcMonitor.register();
        AdaptiveTaskScheduler.getInstance().start();
        if (workerRunning) {
            return;
        }
        workerRunning = true;
        workerThread = new Thread(this::workerLoop, "S1ind-APE-Worker");
        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.MIN_PRIORITY);
        workerThread.start();
    }

    public synchronized void stopBackgroundWorker() {
        workerRunning = false;
        AdaptiveTaskScheduler.getInstance().stop();
        GcMonitor.unregister();
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

    /**
     * Records a single client frame duration in nanoseconds (O(1), zero allocations).
     */
    public void recordFrame(long durationNano) {
        if (durationNano <= 0) return;
        this.clientFrameRecorded = true;
        synchronized (batchLock) {
            if (activeBatchCount < BATCH_CAPACITY) {
                activeFrameBatch[activeBatchCount++] = durationNano;
            } else {
                activeFrameBatch[BATCH_CAPACITY - 1] = durationNano;
            }
        }
    }

    /**
     * Records a server simulation tick duration in nanoseconds.
     */
    public void recordTick(long durationNano) {
        if (durationNano <= 0) return;
        this.serverTickRecorded = true;
        synchronized (tickLock) {
            rollingTicks[tickHead] = durationNano;
            tickHead = (tickHead + 1) % TICK_BUFFER_SIZE;
            if (tickCount < TICK_BUFFER_SIZE) {
                tickCount++;
            }
        }
    }

    public void updateClientRenderSettings(int maxFps, boolean vsync, boolean integratedRunning) {
        if (maxFps > 0) {
            this.targetFpsCap = maxFps;
        }
        this.vsyncEnabled = vsync;
        this.integratedServerActive = integratedRunning;
    }

    public void onLocalServerStopped() {
        this.serverTickRecorded = false;
        this.integratedServerActive = false;
        this.cachedMspt = 0.0;
        synchronized (tickLock) {
            tickHead = 0;
            tickCount = 0;
        }
    }

    private void workerLoop() {
        while (workerRunning) {
            try {
                Thread.sleep(500L);
                recalculateNow(System.nanoTime());

                // Dispatch auxiliary maintenance cleanly through AdaptiveTaskScheduler
                AdaptiveTaskScheduler.getInstance().submit(
                    PerformanceTelemetry::refreshSnapshot,
                    TaskPriority.HIGH,
                    "telemetry-snapshot"
                );
                AdaptiveTaskScheduler.getInstance().submit(
                    GcMonitor::refreshWindow,
                    TaskPriority.NORMAL,
                    "gc-window-prune"
                );
            } catch (InterruptedException e) {
                if (!workerRunning) break;
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                S1indOptMod.LOG.warn("[S1ind] APE background cycle exception", e);
            }
        }
    }

    /**
     * Drains incoming frame batch, appends into 20k rolling array without allocation,
     * computes true arithmetic mean of slowest 1% and 0.1%, evaluates monotonic stress,
     * and refreshes pre-formatted telemetry snapshot.
     */
    public synchronized void recalculateNow(long nowNano) {
        // 1. Drain frame batch via O(1) reference swap
        int drainedCount;
        synchronized (batchLock) {
            long[] swap = activeFrameBatch;
            activeFrameBatch = drainFrameBatch;
            drainFrameBatch = swap;
            drainedCount = activeBatchCount;
            activeBatchCount = 0;
        }

        for (int i = 0; i < drainedCount; i++) {
            rollingFrames[rollingHead] = drainFrameBatch[i];
            rollingHead = (rollingHead + 1) % FRAME_BUFFER_SIZE;
            if (rollingCount < FRAME_BUFFER_SIZE) {
                rollingCount++;
            }
        }
        this.cachedSampleCount = rollingCount;

        // 2. Readiness & True Mean of Worst 1% / 0.1%
        if (!clientFrameRecorded || rollingCount < WARMUP_MIN_FRAMES) {
            this.readiness = TelemetryReadiness.INITIALIZING;
            if (rollingCount > 0) {
                // Only compute simple mean FPS during early warmup; withhold 1%/0.1% Low claims
                long sum = 0;
                for (int i = 0; i < rollingCount; i++) sum += rollingFrames[i];
                double meanNano = (double) sum / rollingCount;
                this.cachedAvgFrameTimeMs = meanNano / 1_000_000.0;
                this.cachedAvgFps = meanNano > 0 ? (1_000_000_000.0 / meanNano) : 0.0;
            }
            this.cachedOnePercentLow = 0.0;
            this.cachedPointOnePercentLow = 0.0;
            this.cachedOnePercentSampleSize = 0;
            this.cachedPointOnePercentSampleSize = 0;
        } else {
            this.readiness = (rollingCount >= FRAME_BUFFER_SIZE)
                ? TelemetryReadiness.STABLE
                : TelemetryReadiness.PARTIAL;

            System.arraycopy(rollingFrames, 0, sortWorkspace, 0, rollingCount);
            Arrays.sort(sortWorkspace, 0, rollingCount);

            long sumAll = 0;
            for (int i = 0; i < rollingCount; i++) {
                sumAll += sortWorkspace[i];
            }
            double meanNano = (double) sumAll / rollingCount;
            this.cachedAvgFrameTimeMs = meanNano / 1_000_000.0;
            this.cachedAvgFps = meanNano > 0 ? (1_000_000_000.0 / meanNano) : 0.0;

            // Mean of slowest 1% (200 frames at 20,000)
            int count1Pct = Math.max(1, rollingCount / 100);
            long sum1Pct = 0;
            for (int i = rollingCount - count1Pct; i < rollingCount; i++) {
                sum1Pct += sortWorkspace[i];
            }
            double mean1PctNano = (double) sum1Pct / count1Pct;
            this.cachedOnePercentLow = mean1PctNano > 0 ? (1_000_000_000.0 / mean1PctNano) : 0.0;
            this.cachedOnePercentSampleSize = count1Pct;

            // Mean of slowest 0.1% (20 frames at 20,000)
            int count01Pct = Math.max(1, rollingCount / 1000);
            long sum01Pct = 0;
            for (int i = rollingCount - count01Pct; i < rollingCount; i++) {
                sum01Pct += sortWorkspace[i];
            }
            double mean01PctNano = (double) sum01Pct / count01Pct;
            this.cachedPointOnePercentLow = mean01PctNano > 0 ? (1_000_000_000.0 / mean01PctNano) : 0.0;
            this.cachedPointOnePercentSampleSize = count01Pct;
        }

        // 3. Server MSPT
        if (serverTickRecorded) {
            long totalTickNano = 0;
            int tCount;
            synchronized (tickLock) {
                tCount = tickCount;
                for (int i = 0; i < tCount; i++) {
                    totalTickNano += rollingTicks[i];
                }
            }
            if (tCount > 0) {
                this.cachedMspt = (double) totalTickNano / (tCount * 1_000_000.0);
            }
        } else {
            this.cachedMspt = 0.0;
        }

        // 4. Refresh GC window synchronously as well for deterministic tests
        GcMonitor.refreshWindow();

        // 5. Evaluate Stress with Hysteresis & Monotonic Dwell
        StressLevel rawTarget = evaluateRawStress(this.currentStress);
        updateStressWithHysteresis(rawTarget, nowNano);

        // 6. Classify Bottleneck + Confidence
        evaluateBottleneck();

        // 7. Refresh cached UI snapshot strings off the Render Thread
        PerformanceTelemetry.refreshSnapshot();
    }

    private StressLevel evaluateRawStress(StressLevel current) {
        double mspt = serverTickRecorded ? this.cachedMspt : 0.0;

        // Dedicated server / headless path: purely MSPT-driven
        if (!clientFrameRecorded) {
            return switch (current) {
                case NORMAL -> {
                    if (mspt > 46.0) yield StressLevel.CRITICAL;
                    if (mspt > 36.0) yield StressLevel.MODERATE;
                    yield StressLevel.NORMAL;
                }
                case MODERATE -> {
                    if (mspt > 46.0) yield StressLevel.CRITICAL;
                    if (mspt <= 30.0) yield StressLevel.NORMAL;
                    yield StressLevel.MODERATE;
                }
                case CRITICAL -> {
                    if (mspt <= 40.0) yield StressLevel.MODERATE;
                    yield StressLevel.CRITICAL;
                }
            };
        }

        // During initialization (<500 frames), rely strictly on MSPT so startup loading doesn't trip CRITICAL
        if (readiness == TelemetryReadiness.INITIALIZING) {
            return mspt > 46.0 ? StressLevel.CRITICAL : StressLevel.NORMAL;
        }

        // Compute dynamic expected FPS baseline respecting actual FPS limit (without clipping 120/144 down to 60!)
        double targetCeiling = (targetFpsCap > 0 && targetFpsCap <= 250) ? targetFpsCap : -1.0;

        double fps = this.cachedAvgFps;
        double low1 = this.cachedOnePercentLow;

        // For stress shedding (when should APE conserve CPU?), use playable floor OR relative drop against low caps:
        double modFpsThresh = (targetCeiling > 0 && targetCeiling < 60.0) ? targetCeiling * 0.80 : 48.0;
        double critFpsThresh = (targetCeiling > 0 && targetCeiling < 60.0) ? targetCeiling * 0.55 : 32.0;
        double modLow1Thresh = (targetCeiling > 0 && targetCeiling < 60.0) ? targetCeiling * 0.58 : 35.0;
        double critLow1Thresh = (targetCeiling > 0 && targetCeiling < 60.0) ? targetCeiling * 0.36 : 22.0;

        double recNormFps = (targetCeiling > 0 && targetCeiling < 60.0) ? targetCeiling * 0.90 : 55.0;
        double recNormLow1 = (targetCeiling > 0 && targetCeiling < 60.0) ? targetCeiling * 0.70 : 42.0;
        double recModFps = (targetCeiling > 0 && targetCeiling < 60.0) ? targetCeiling * 0.70 : 42.0;
        double recModLow1 = (targetCeiling > 0 && targetCeiling < 60.0) ? targetCeiling * 0.50 : 30.0;

        return switch (current) {
            case NORMAL -> {
                if (fps < critFpsThresh || low1 < critLow1Thresh || mspt > 46.0) yield StressLevel.CRITICAL;
                if (fps < modFpsThresh || low1 < modLow1Thresh || mspt > 36.0) yield StressLevel.MODERATE;
                yield StressLevel.NORMAL;
            }
            case MODERATE -> {
                if (fps < critFpsThresh || low1 < critLow1Thresh || mspt > 46.0) yield StressLevel.CRITICAL;
                if (fps >= recNormFps && low1 >= recNormLow1 && mspt <= 30.0) yield StressLevel.NORMAL;
                yield StressLevel.MODERATE;
            }
            case CRITICAL -> {
                if (fps >= recModFps && low1 >= recModLow1 && mspt <= 40.0) yield StressLevel.MODERATE;
                yield StressLevel.CRITICAL;
            }
        };
    }

    private void updateStressWithHysteresis(StressLevel rawTarget, long nowNano) {
        if (rawTarget == this.currentStress) {
            this.candidateStress = this.currentStress;
            this.candidateSinceNano = nowNano;
            return;
        }

        if (rawTarget != this.candidateStress) {
            this.candidateStress = rawTarget;
            this.candidateSinceNano = nowNano;
            return;
        }

        boolean isEscalating = rawTarget.ordinal() > this.currentStress.ordinal();
        long escalateDwell = (S1indOptMod.config() != null && S1indOptMod.config().profile() == PerformanceProfile.PERFORMANCE)
            ? PERFORMANCE_ESCALATE_DWELL_NANOS
            : DEFAULT_ESCALATE_DWELL_NANOS;

        long requiredDwell = isEscalating ? escalateDwell : RECOVERY_DWELL_NANOS;

        if (nowNano - this.candidateSinceNano >= requiredDwell) {
            this.currentStress = rawTarget;
            this.candidateSinceNano = nowNano;
        }
    }

    private void evaluateBottleneck() {
        if (GcMonitor.isUnderGcPressure()) {
            this.bottleneckType = BottleneckType.GC_PRESSURE;
            this.bottleneckConfidence = GcMonitor.getMaxDuration60s() >= 100L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM;
            return;
        }

        if (serverTickRecorded && cachedMspt > 38.0) {
            this.bottleneckType = BottleneckType.CPU_BOUND;
            this.bottleneckConfidence = cachedMspt >= 46.0 ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM;
            return;
        }

        if (!clientFrameRecorded || readiness == TelemetryReadiness.INITIALIZING) {
            this.bottleneckType = BottleneckType.UNKNOWN;
            this.bottleneckConfidence = ConfidenceLevel.LOW;
            return;
        }

        // Evaluate frame budget strictly relative to configured FPS Cap when present
        boolean hasExplicitCap = targetFpsCap > 0 && targetFpsCap <= 250;
        if (hasExplicitCap) {
            double targetBudgetMs = 1000.0 / targetFpsCap;
            // Missing target frame budget by >25% while server logic MSPT is fast (or remote)
            if (cachedAvgFrameTimeMs > targetBudgetMs * 1.25 && (!serverTickRecorded || cachedMspt < 28.0)) {
                this.bottleneckType = BottleneckType.CLIENT_RENDER_BOUND;
                this.bottleneckConfidence = serverTickRecorded ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM;
                return;
            }
        } else if (!vsyncEnabled) {
            // Uncapped & no VSync: flag only on clear render slowdown (>22ms / <45 FPS) without assuming a fixed 60 target
            if (cachedAvgFrameTimeMs > 22.0 && (!serverTickRecorded || cachedMspt < 28.0)) {
                this.bottleneckType = BottleneckType.CLIENT_RENDER_BOUND;
                this.bottleneckConfidence = serverTickRecorded ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW;
                return;
            }
        } else {
            // VSync active without known display refresh rate -> flag only if heavy slowdown >22ms
            if (cachedAvgFrameTimeMs > 22.0 && (!serverTickRecorded || cachedMspt < 28.0)) {
                this.bottleneckType = BottleneckType.CLIENT_RENDER_BOUND;
                this.bottleneckConfidence = ConfidenceLevel.LOW;
                return;
            }
        }

        this.bottleneckType = BottleneckType.UNKNOWN;
        this.bottleneckConfidence = ConfidenceLevel.HIGH; // Healthy/Balanced
    }

    public synchronized void resetForTesting() {
        synchronized (batchLock) {
            activeBatchCount = 0;
        }
        synchronized (tickLock) {
            tickHead = 0;
            tickCount = 0;
        }
        rollingHead = 0;
        rollingCount = 0;
        cachedSampleCount = 0;
        serverTickRecorded = false;
        clientFrameRecorded = false;
        integratedServerActive = false;
        readiness = TelemetryReadiness.INITIALIZING;
        targetFpsCap = 260;
        vsyncEnabled = false;
        currentStress = StressLevel.NORMAL;
        candidateStress = StressLevel.NORMAL;
        candidateSinceNano = System.nanoTime();
        cachedAvgFps = 0.0;
        cachedAvgFrameTimeMs = 0.0;
        cachedOnePercentLow = 0.0;
        cachedPointOnePercentLow = 0.0;
        cachedOnePercentSampleSize = 0;
        cachedPointOnePercentSampleSize = 0;
        cachedMspt = 0.0;
        bottleneckType = BottleneckType.UNKNOWN;
        bottleneckConfidence = ConfidenceLevel.LOW;
        GcMonitor.resetForTesting();
        PerformanceTelemetry.refreshSnapshot();
    }

    public int getSampledFrameCount()               { return cachedSampleCount; }
    public TelemetryReadiness getReadiness()        { return readiness; }
    public double getAvgFps()                       { return cachedAvgFps; }
    public double getAvgFrameTimeMs()               { return cachedAvgFrameTimeMs; }
    public double getOnePercentLow()                { return cachedOnePercentLow; }
    public double getPointOnePercentLow()           { return cachedPointOnePercentLow; }
    public int getOnePercentSampleSize()            { return cachedOnePercentSampleSize; }
    public int getPointOnePercentSampleSize()       { return cachedPointOnePercentSampleSize; }
    public double getMspt()                         { return cachedMspt; }
    public boolean isServerTickRecorded()           { return serverTickRecorded; }
    public boolean isIntegratedServerActive()       { return integratedServerActive; }
    public boolean isWorkerRunning()                { return workerRunning; }
    public StressLevel getStressLevel()             { return currentStress; }
    public BottleneckType getBottleneckType()       { return bottleneckType; }
    public ConfidenceLevel getBottleneckConfidence(){ return bottleneckConfidence; }
}
