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

        // Baseline NORMAL: 100 FPS
        for (int i = 0; i < 600; i++) ape.recordFrame(10_000_000L);
        ape.recalculateNow(t);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel());

        // Drop to MODERATE (~42 FPS = 24ms)
        for (int i = 0; i < 20_000; i++) ape.recordFrame(24_000_000L);

        // Brief spike (<3s dwell) MUST NOT trigger escalation (anti-flapping)
        ape.recalculateNow(t + 1_000_000_000L);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel());

        // Held past 3.0s dwell -> escalates to MODERATE
        ape.recalculateNow(t + 4_100_000_000L);
        assertEquals(StressLevel.MODERATE, ape.getStressLevel());

        // Recover slightly to 50 FPS (20ms): inside hysteresis deadband (MODERATE->NORMAL needs >=55 FPS)
        for (int i = 0; i < 20_000; i++) ape.recordFrame(20_000_000L);
        ape.recalculateNow(t + 10_000_000_000L);
        assertEquals(StressLevel.MODERATE, ape.getStressLevel(), "Hysteresis deadband prevents flapping at 50 FPS");

        // Recover strongly to 100 FPS (10ms) and hold past 5.0s recovery dwell
        for (int i = 0; i < 20_000; i++) ape.recordFrame(10_000_000L);
        ape.recalculateNow(t + 11_000_000_000L);
        assertEquals(StressLevel.MODERATE, ape.getStressLevel(), "Must hold 5s before de-escalating");
        ape.recalculateNow(t + 16_200_000_000L);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel());
    }

    @Test
    void testDedicatedServerEvaluatesHeadlessMsptWithoutClientFrames() {
        long t = 10_000_000_000L;
        for (int i = 0; i < 40; i++) ape.recordTick(49_000_000L); // 49ms MSPT

        ape.recalculateNow(t);
        ape.recalculateNow(t + 3_500_000_000L);

        assertEquals(StressLevel.CRITICAL, ape.getStressLevel());
        assertEquals(BottleneckType.CPU_BOUND, ape.getBottleneckType());
        assertEquals(ConfidenceLevel.HIGH, ape.getBottleneckConfidence());
    }

    @Test
    void testHighAndLowFpsCapBottleneckAwareness() {
        long t = 20_000_000_000L;

        // 1) User capped at 30 FPS -> 33.3ms frames must NOT trigger CRITICAL or CLIENT_RENDER_BOUND
        ape.updateClientRenderSettings(30, false, true);
        for (int i = 0; i < 600; i++) {
            ape.recordFrame(33_333_333L);
            ape.recordTick(10_000_000L);
        }
        ape.recalculateNow(t);
        ape.recalculateNow(t + 5_000_000_000L);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel());
        assertEquals(BottleneckType.UNKNOWN, ape.getBottleneckType());

        // 2) User capped at 144 FPS -> missing budget at 80 FPS (12.5ms) with fast tick (8ms) detects CLIENT_RENDER_BOUND
        ape.resetForTesting();
        ape.updateClientRenderSettings(144, false, true);
        for (int i = 0; i < 600; i++) {
            ape.recordFrame(12_500_000L); // 80 FPS (< 144 target budget 6.94ms * 1.25 = 8.68ms)
            ape.recordTick(8_000_000L);
        }
        ape.recalculateNow(t + 6_000_000_000L);
        assertEquals(BottleneckType.CLIENT_RENDER_BOUND, ape.getBottleneckType());
        assertEquals(ConfidenceLevel.HIGH, ape.getBottleneckConfidence());
    }

    @Test
    void testStartStopAndCleanRestartLifecycle() throws InterruptedException {
        assertFalse(ape.isWorkerRunning());
        ape.startBackgroundWorker();
        assertTrue(ape.isWorkerRunning());
        assertTrue(AdaptiveTaskScheduler.getInstance().isRunning());

        ape.stopBackgroundWorker();
        assertFalse(ape.isWorkerRunning());
        assertFalse(AdaptiveTaskScheduler.getInstance().isRunning());

        // Re-start cleanly
        ape.startBackgroundWorker();
        assertTrue(ape.isWorkerRunning());
        ape.stopBackgroundWorker();
        assertFalse(ape.isWorkerRunning());
    }
}
