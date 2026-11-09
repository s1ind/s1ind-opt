package com.s1ind.opt.engine;

import java.util.Locale;

/**
 * Formats telemetry data for display in F3 debug HUD and console logs.
 */
public final class PerformanceTelemetry {

    private PerformanceTelemetry() {}

    public static String formatSummary() {
        AdaptivePerformanceEngine ape = AdaptivePerformanceEngine.getInstance();
        return String.format(
            Locale.ROOT,
            "FPS: %.0f | 1%% Low: %.0f | 0.1%% Low: %.0f | MSPT: %.1fms | GC: %dms | Stress: %s",
            ape.getAvgFps(),
            ape.getOnePercentLow(),
            ape.getPointOnePercentLow(),
            ape.getMspt(),
            ape.getTotalGcPauseMs(),
            ape.getStressLevel().name()
        );
    }

    public static String formatCompact() {
        AdaptivePerformanceEngine ape = AdaptivePerformanceEngine.getInstance();
        return String.format(
            Locale.ROOT,
            "1%%: %.0f | 0.1%%: %.0f | MSPT: %.1fms",
            ape.getOnePercentLow(),
            ape.getPointOnePercentLow(),
            ape.getMspt()
        );
    }
}
