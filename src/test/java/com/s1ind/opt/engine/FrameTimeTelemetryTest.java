package com.s1ind.opt.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameTimeTelemetryTest {

    private AdaptivePerformanceEngine ape;

    @BeforeEach
    void setUp() {
        ape = AdaptivePerformanceEngine.getInstance();
        ape.resetForTesting();
    }

    @Test
    void testEmptyBufferInitializesSafely() {
        ape.recalculateNow(1_000_000_000L);
        assertEquals(0, ape.getSampledFrameCount());
        assertEquals(TelemetryReadiness.INITIALIZING, ape.getReadiness());
        assertEquals(0.0, ape.getOnePercentLow());
        assertEquals(0.0, ape.getPointOnePercentLow());
    }

    @Test
    void testSingleFrameWithholdsLowMetrics() {
        ape.recordFrame(10_000_000L); // 10ms -> 100 FPS
        ape.recalculateNow(1_000_000_000L);

        assertEquals(1, ape.getSampledFrameCount());
        assertEquals(TelemetryReadiness.INITIALIZING, ape.getReadiness());
        assertEquals(100.0, ape.getAvgFps(), 0.5);
        assertEquals(0.0, ape.getOnePercentLow(), "1% Low must stay withheld during INITIALIZING");
    }

    @Test
    void testReadinessTransitionsAt500And19999And20000() {
        for (int i = 0; i < 499; i++) {
            ape.recordFrame(10_000_000L);
        }
        ape.recalculateNow(1_000_000_000L);
        assertEquals(TelemetryReadiness.INITIALIZING, ape.getReadiness());

        ape.recordFrame(10_000_000L); // 500th frame
        ape.recalculateNow(2_000_000_000L);
        assertEquals(TelemetryReadiness.PARTIAL, ape.getReadiness());
        assertEquals(5, ape.getOnePercentSampleSize());
        assertEquals(1, ape.getPointOnePercentSampleSize());

        for (int i = 500; i < 19_999; i++) {
            ape.recordFrame(10_000_000L);
        }
        ape.recalculateNow(3_000_000_000L);
        assertEquals(TelemetryReadiness.PARTIAL, ape.getReadiness());
        assertEquals(19_999, ape.getSampledFrameCount());

        ape.recordFrame(10_000_000L); // 20,000th frame
        ape.recalculateNow(4_000_000_000L);
        assertEquals(TelemetryReadiness.STABLE, ape.getReadiness());
        assertEquals(20_000, ape.getSampledFrameCount());
        assertEquals(200, ape.getOnePercentSampleSize());
        assertEquals(20, ape.getPointOnePercentSampleSize());
    }

    @Test
    void testIdenticalFrameTimesYieldIdenticalPercentiles() {
        for (int i = 0; i < 20_000; i++) {
            ape.recordFrame(10_000_000L); // Exact 100 FPS
        }
        ape.recalculateNow(1_000_000_000L);

        assertEquals(100.0, ape.getAvgFps(), 0.01);
        assertEquals(100.0, ape.getOnePercentLow(), 0.01);
        assertEquals(100.0, ape.getPointOnePercentLow(), 0.01);
    }

    @Test
    void testTrueArithmeticMeanOfSlowest200And20WithExtremeMicrospikes() {
        for (int i = 0; i < 19_800; i++) {
            ape.recordFrame(8_000_000L); // 125 FPS
        }
        for (int i = 0; i < 180; i++) {
            ape.recordFrame(20_000_000L); // 50 FPS
        }
        // 20 extreme 500ms micro-spikes (0.1% worst)
        for (int i = 0; i < 20; i++) {
            ape.recordFrame(500_000_000L);
        }
        ape.recalculateNow(1_000_000_000L);

        // Worst 20 mean = 500ms -> 2.0 FPS
        assertEquals(2.0, ape.getPointOnePercentLow(), 0.05);

        // Worst 200 mean = (180*20ms + 20*500ms) / 200 = 13600 / 200 = 68ms -> 14.706 FPS
        double expected1Pct = 1_000_000_000.0 / 68_000_000.0;
        assertEquals(expected1Pct, ape.getOnePercentLow(), 0.1);
    }

    @Test
    void testRingBufferOverflowOverwritesOldestFrames() {
        // Fill initial 20,000 slow frames (50ms -> 20 FPS)
        for (int i = 0; i < 20_000; i++) {
            ape.recordFrame(50_000_000L);
        }
        ape.recalculateNow(1_000_000_000L);
        assertEquals(20.0, ape.getAvgFps(), 0.1);

        // Push 20,000 fast frames (10ms -> 100 FPS) wrapping cleanly over all slow frames
        for (int i = 0; i < 20_000; i++) {
            ape.recordFrame(10_000_000L);
        }
        ape.recalculateNow(2_000_000_000L);

        assertEquals(20_000, ape.getSampledFrameCount());
        assertEquals(100.0, ape.getAvgFps(), 0.1);
        assertEquals(100.0, ape.getOnePercentLow(), 0.1);
        assertEquals(100.0, ape.getPointOnePercentLow(), 0.1);
    }
}
