package com.s1ind.opt.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptivePerformanceEngineLifecycleTest {

    private AdaptivePerformanceEngine ape;

    @BeforeEach
    void setUp() {
        ape = AdaptivePerformanceEngine.getInstance();
        ape.stopBackgroundWorker();
        ape.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        ape.stopBackgroundWorker();
    }

    @Test
    void testStressTransitionsHysteresisAndAntiFlapping() {
        long t = 10_000_000_000L;

        for (int i = 0; i < 600; i++) ape.recordFrame(10_000_000L);
        ape.recalculateNow(t);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel());

        for (int i = 0; i < 20_000; i++) ape.recordFrame(24_000_000L); // ~41.6 FPS
        ape.recalculateNow(t + 1_000_000_000L);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel());

        ape.recalculateNow(t + 4_100_000_000L);
        assertEquals(StressLevel.MODERATE, ape.getStressLevel());

        for (int i = 0; i < 20_000; i++) ape.recordFrame(20_000_000L); // 50 FPS deadband
        ape.recalculateNow(t + 10_000_000_000L);
        assertEquals(StressLevel.MODERATE, ape.getStressLevel());

        for (int i = 0; i < 20_000; i++) ape.recordFrame(10_000_000L); // 100 FPS
        ape.recalculateNow(t + 11_000_000_000L);
        assertEquals(StressLevel.MODERATE, ape.getStressLevel());
        ape.recalculateNow(t + 16_200_000_000L);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel());
    }

    @Test
    void testServerModerateStressEvaluatedEvenDuringClientWarmup() {
        long t = 10_000_000_000L;
        // Only 50 client frames (INITIALIZING), but integrated server has 40ms MSPT (MODERATE)
        for (int i = 0; i < 50; i++) {
            ape.recordFrame(10_000_000L);
            ape.recordTick(40_000_000L);
        }
        ape.recalculateNow(t);
        ape.recalculateNow(t + 3_500_000_000L);

        assertEquals(TelemetryReadiness.INITIALIZING, ape.getReadiness());
        assertEquals(StressLevel.MODERATE, ape.getStressLevel(), "Server MODERATE load must not be suppressed during client warmup");
        assertEquals(BottleneckType.SERVER_TICK_BOUND, ape.getBottleneckType());
    }

    @Test
    void testDedicatedServerEvaluatesHeadlessMsptWithoutClientFrames() {
        long t = 10_000_000_000L;
        for (int i = 0; i < 40; i++) ape.recordTick(49_000_000L);

        ape.recalculateNow(t);
        ape.recalculateNow(t + 3_500_000_000L);

        assertEquals(StressLevel.CRITICAL, ape.getStressLevel());
        assertEquals(BottleneckType.SERVER_TICK_BOUND, ape.getBottleneckType());
        assertEquals(ConfidenceLevel.HIGH, ape.getBottleneckConfidence());
    }

    @Test
    void testUnknownBottleneckNeverReturnsHighConfidenceAndRespectsFullFpsCaps() {
        long t = 20_000_000_000L;

        // 0) Empty telemetry -> UNKNOWN + LOW confidence (NEVER HIGH!)
        ape.recalculateNow(t);
        assertEquals(BottleneckType.UNKNOWN, ape.getBottleneckType());
        assertNotEquals(ConfidenceLevel.HIGH, ape.getBottleneckConfidence());

        // 1) 30 FPS Cap -> 33.33ms stays NORMAL & NO_CLEAR_BOTTLENECK
        ape.updateClientRenderSettings(30, false, true);
        for (int i = 0; i < 600; i++) {
            ape.recordFrame(33_333_333L);
            ape.recordTick(10_000_000L);
        }
        ape.recalculateNow(t + 1_000_000_000L);
        ape.recalculateNow(t + 5_000_000_000L);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel());
        assertEquals(BottleneckType.NO_CLEAR_BOTTLENECK, ape.getBottleneckType());

        // 2) 120 FPS Cap -> 120 FPS (8.33ms) is NO_CLEAR_BOTTLENECK; dropping to 80 FPS (12.5ms) detects CLIENT_RENDER_BOUND
        ape.resetForTesting();
        ape.updateClientRenderSettings(120, false, true);
        for (int i = 0; i < 600; i++) {
            ape.recordFrame(8_333_333L);
            ape.recordTick(8_000_000L);
        }
        ape.recalculateNow(t + 6_000_000_000L);
        assertEquals(BottleneckType.NO_CLEAR_BOTTLENECK, ape.getBottleneckType());

        ape.resetForTesting();
        ape.updateClientRenderSettings(240, false, true);
        for (int i = 0; i < 600; i++) {
            ape.recordFrame(6_000_000L); // 166 FPS (< 240 FPS budget 4.17ms * 1.25 = 5.21ms)
            ape.recordTick(6_000_000L);
        }
        ape.recalculateNow(t + 7_000_000_000L);
        assertEquals(BottleneckType.CLIENT_RENDER_BOUND, ape.getBottleneckType());
    }

    @Test
    void testStartStopAndCleanRestartWithoutDuplicateWorkers() {
        assertFalse(ape.isWorkerRunning());
        ape.startBackgroundWorker();
        assertTrue(ape.isWorkerRunning());
        assertTrue(AdaptiveTaskScheduler.getInstance().isRunning());

        // Repeated start must not leak a second worker thread
        ape.startBackgroundWorker();
        long apeThreads = Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.isAlive() && t.getName().equals("S1ind-APE-Worker"))
            .count();
        assertEquals(1, apeThreads);

        ape.stopBackgroundWorker();
        assertFalse(ape.isWorkerRunning());
        assertFalse(AdaptiveTaskScheduler.getInstance().isRunning());
    }
}
