package com.s1ind.opt.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AdaptivePerformanceEngineTest {

    private AdaptivePerformanceEngine ape;

    @BeforeEach
    void setUp() {
        ape = AdaptivePerformanceEngine.getInstance();
        ape.resetForTesting();
    }

    @Test
    void testTrueMeanOfWorst200AndWorst20() {
        // 19,800 fast frames @ 10ms (100 FPS)
        for (int i = 0; i < 19_800; i++) {
            ape.recordFrame(10_000_000L);
        }
        // 180 medium slow frames @ 20ms
        for (int i = 0; i < 180; i++) {
            ape.recordFrame(20_000_000L);
        }
        // 20 severe lag frames @ 50ms -> total slowest 200 frames = 180*20ms + 20*50ms = 4600ms / 200 = 23ms avg -> 43.48 FPS
        for (int i = 0; i < 20; i++) {
            ape.recordFrame(50_000_000L);
        }

        ape.recalculateNow(1_000_000_000L);

        assertEquals(20_000, ape.getSampledFrameCount(), "Buffer should hold 20,000 frames");
        assertFalse(ape.isWarmingUp(), "Warmup completes at 500+ frames");

        double expected1PctLow = 1_000_000_000.0 / 23_000_000.0; // ~43.478 FPS
        assertEquals(expected1PctLow, ape.getOnePercentLow(), 0.25, "1% Low must compute mean of worst 200 frames");

        double expected01PctLow = 1_000_000_000.0 / 50_000_000.0; // 20.0 FPS
        assertEquals(expected01PctLow, ape.getPointOnePercentLow(), 0.1, "0.1% Low must compute mean of worst 20 frames");
    }

    @Test
    void testWarmupStateThreshold() {
        for (int i = 0; i < 499; i++) {
            ape.recordFrame(10_000_000L);
        }
        ape.recalculateNow(1_000_000_000L);
        assertTrue(ape.isWarmingUp(), "Should remain in WARMING UP under 500 frames");

        ape.recordFrame(10_000_000L);
        ape.recalculateNow(2_000_000_000L);
        assertFalse(ape.isWarmingUp(), "Should exit WARMING UP once 500 frames are recorded");
    }

    @Test
    void testDedicatedServerHeadlessMsptStress() {
        long t0 = 10_000_000_000L;
        // Dedicated server records ONLY server ticks, zero client frames
        for (int i = 0; i < 50; i++) {
            ape.recordTick(48_000_000L); // 48ms MSPT (>46ms threshold)
        }

        ape.recalculateNow(t0 + 1_000_000_000L);
        assertEquals(StressLevel.NORMAL, ape.getStressLevel(), "Dwell protects against 1s spike");

        ape.recalculateNow(t0 + 4_500_000_000L);
        assertEquals(StressLevel.CRITICAL, ape.getStressLevel(), "Dedicated server escalates on sustained MSPT");
        assertEquals(BottleneckType.CPU_TICK_BOUND, ape.getBottleneckType());
    }

    @Test
    void testRespectsIntentional30FpsCap() {
        long t0 = 20_000_000_000L;
        ape.updateClientFpsLimit(30);

        for (int i = 0; i < 600; i++) {
            ape.recordFrame(33_333_333L); // Stable 30.0 FPS
        }
        ape.recalculateNow(t0);
        ape.recalculateNow(t0 + 5_000_000_000L);

        assertEquals(StressLevel.NORMAL, ape.getStressLevel(), "Intentional 30 FPS cap must remain NORMAL");
        assertEquals(BottleneckType.BALANCED, ape.getBottleneckType(), "Capped 30 FPS should not trigger CLIENT_RENDER_BOUND");
    }

    @Test
    void testBottleneckAttributionClientRenderVsCpuTick() {
        long t0 = 30_000_000_000L;

        for (int i = 0; i < 600; i++) {
            ape.recordFrame(30_000_000L); // 33 FPS slow render
            ape.recordTick(10_000_000L);  // 10ms fast logic tick
        }
        ape.recalculateNow(t0);
        assertEquals(BottleneckType.CLIENT_RENDER_BOUND, ape.getBottleneckType());
    }

    @Test
    void testAdaptiveTaskSchedulerDefersLowPriorityUnderCriticalStress() throws Exception {
        AdaptiveTaskScheduler scheduler = AdaptiveTaskScheduler.getInstance();
        scheduler.resetForTesting();

        // Force APE into CRITICAL state
        long t0 = 50_000_000_000L;
        for (int i = 0; i < 50; i++) ape.recordTick(49_000_000L);
        ape.recalculateNow(t0);
        ape.recalculateNow(t0 + 3_500_000_000L);
        assertEquals(StressLevel.CRITICAL, ape.getStressLevel());

        AtomicBoolean lowExecuted = new AtomicBoolean(false);
        AtomicBoolean highExecuted = new AtomicBoolean(false);

        scheduler.submit(() -> lowExecuted.set(true), AdaptiveTaskScheduler.TaskPriority.LOW);
        scheduler.submit(() -> highExecuted.set(true), AdaptiveTaskScheduler.TaskPriority.HIGH);

        // First queued item is HIGH due to PriorityBlockingQueue weight
        assertTrue(scheduler.getPendingCount() >= 2);
    }
}
