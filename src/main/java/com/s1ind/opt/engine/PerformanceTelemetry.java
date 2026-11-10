package com.s1ind.opt.engine;

import net.minecraft.client.MinecraftClient;

import java.util.Locale;

/**
 * Formats live runtime telemetry for F3 debug HUD.
 */
public final class PerformanceTelemetry {

    private PerformanceTelemetry() {}

    public static String formatLine1() {
        AdaptivePerformanceEngine ape = AdaptivePerformanceEngine.getInstance();

        if (ape.isWarmingUp()) {
            return String.format(
                Locale.ROOT,
                "FPS: %.0f (%.1fms) | WARMING UP (%d/%d frames)",
                ape.getAvgFps(),
                ape.getAvgFrameTimeMs(),
                ape.getSampledFrameCount(),
                AdaptivePerformanceEngine.WARMUP_MIN_FRAMES
            );
        }

        return String.format(
            Locale.ROOT,
            "FPS: %.0f (%.1fms) | 1%% Low: %.0f | 0.1%% Low: %.0f (n=%d)",
            ape.getAvgFps(),
            ape.getAvgFrameTimeMs(),
            ape.getOnePercentLow(),
            ape.getPointOnePercentLow(),
            ape.getSampledFrameCount()
        );
    }

    public static String formatLine2() {
        AdaptivePerformanceEngine ape = AdaptivePerformanceEngine.getInstance();
        MinecraftClient client = MinecraftClient.getInstance();
        boolean integrated = client != null && client.isIntegratedServerRunning();

        String msptPart = integrated
            ? String.format(Locale.ROOT, "%.1fms", ape.getMspt())
            : "Remote";

        int deferred = AdaptiveTaskScheduler.getInstance().getDeferredCount();

        return String.format(
            Locale.ROOT,
            "MSPT: %s | GC Cycle: %dms (Max60s: %dms, Freq: %d/m) | Stress: %s [%s] | Def: %d",
            msptPart,
            GcMonitor.getLastDurationMs(),
            GcMonitor.getMaxDuration60s(),
            GcMonitor.getGcCount60s(),
            ape.getStressLevel().name(),
            ape.getBottleneckType().name(),
            deferred
        );
    }
}
