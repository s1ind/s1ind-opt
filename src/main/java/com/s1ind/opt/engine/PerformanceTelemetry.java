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
        MinecraftClient client = MinecraftClient.getInstance();
        boolean integrated = client != null && client.isIntegratedServerRunning();
        ape.setLocalServerActive(integrated);

        String msptPart = integrated
            ? String.format(Locale.ROOT, "%.1fms", ape.getMspt())
            : "N/A (Remote)";

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

        return String.format(
            Locale.ROOT,
            "MSPT: %s | GC Pause: %dms (Max: %dms) | Stress: %s [%s]",
            msptPart,
            GcMonitor.getLastPauseMs(),
            GcMonitor.getMaxRecentPauseMs(),
            ape.getStressLevel().name(),
            ape.getBottleneckType().name()
        );
    }
}
