package com.s1ind.opt.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptivePerformanceEngineTest {

    private AdaptivePerformanceEngine ape;

    @BeforeEach
    void setUp() {
        ape = AdaptivePerformanceEngine.getInstance();
        ape.resetForTesting();
    }

    @Test
    void test20kFrameRingBufferPercentiles() {
        // 19,800 fast frames @ 10ms (100 FPS)
        for (int i = 0; i < 19_800; i++) {
            ape.recordFrame(10_000_000L);
        }
        // 180 medium slow frames @ 20ms (50 FPS) -> 1% slowest region
        for (int i = 0; i < 180; i++) {
            ape.recordFrame(20_000_000L);
        }
        // 20 severe lag frames @ 50ms (20 FPS) -> 0.1% slowest region
        for (int i = 0; i < 20; i++) {
            ape.recordFrame(50_000_000L);
        }

        ape.recalculateNow(1000L);

        assertEquals(20_000, ape.getSampledFrameCount(), "Buffer should saturate at 20,000 frames");
        assertTrue(ape.getAvgFps() > 95.0 && ape.getAvgFps() < 100.0, "Average FPS should be ~98 FPS");
        assertEquals(50.0, ape.getOnePercentLow(), 1.0, "1% Low (p99) should accurately detect 50 FPS tier");
        assertEquals(20.0, ape.getPointOnePercentLow(), 1.0, "0.1% Low (p99.9) should accurately detect 20 FPS tier");
    }

    @Test
    void testAntiFlappingHysteresisAndDwell() {
        long t = 10_000L;
        // Establish baseline NORMAL
        for (int i = 0; i < 100; i++) {
            ape.recordFrame(10_000_000L); // 100 FPS
        }
        ape.recalculateNow(t);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel());

        // Inject severe lag spike (40ms = 25 FPS)
        ape.resetForTesting();
        for (int i = 0; i < 500; i++) {
            ape.recordFrame(40_000_000L);
        }

        // After 1 second (below 3s escalation dwell), must still be NORMAL (no knee-jerk flapping!)
        ape.recalculateNow(t + 1_000L);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel(), "Should ignore brief lag under dwell window");

        // Maintain stress for > 3 seconds -> starts candidate at t+1000, confirms at t+4100
        ape.recalculateNow(t + 4_100L);
        assertEquals(StressLevel.CRITICAL, ape.getStressLevel(), "Should escalate to CRITICAL after sustained dwell");
    }

    @Test
    void testBottleneckAttributionCpuVsRender() {
        long t = 50_000L;

        // Scenario 1: Fast render (10ms = 100 FPS), heavy server tick (45ms MSPT)
        for (int i = 0; i < 100; i++) {
            ape.recordFrame(10_000_000L);
            ape.recordTick(45_000_000L);
        }
        ape.recalculateNow(t);
        assertEquals(BottleneckType.CPU_TICK_BOUND, ape.getBottleneckType());

        // Scenario 2: Fast server tick (10ms MSPT), slow render/GPU (33ms = 30 FPS)
        ape.resetForTesting();
        for (int i = 0; i < 100; i++) {
            ape.recordFrame(33_000_000L);
            ape.recordTick(10_000_000L);
        }
        ape.recalculateNow(t + 1_000L);
        assertEquals(BottleneckType.RENDER_GPU_BOUND, ape.getBottleneckType());
    }
}
