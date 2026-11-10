package com.s1ind.opt.engine;

import java.util.Locale;

/**
 * Formats and publishes immutable F3 telemetry snapshots from background analytics.
 *
 * DebugHudMixin consumes getSnapshot() directly without calling String.format()
 * or concatenating strings per rendered frame.
 */
public final class PerformanceTelemetry {

    public record TelemetrySnapshot(
        String rawLine1,
        String rawLine2,
        String rawLine3,
        String f3Line1,
        String f3Line2,
        String f3Line3
    ) {}

    private static volatile TelemetrySnapshot cachedSnapshot = buildInitialSnapshot();

    private static TelemetrySnapshot buildInitialSnapshot() {
        String r1 = "FPS: 0 (0.0ms) | Status: INITIALIZING (n=0/500)";
        String r2 = "MSPT: Waiting | GC Cycle: 0ms (STW Pause: N/A) | Stress: NORMAL | Bottleneck: UNKNOWN (LOW)";
        String r3 = "Deferred Attempts: 0 | Unique Deferred: 0 | Currently Deferred: 0 | Rejected: 0";
        return new TelemetrySnapshot(
            r1, r2, r3,
            "\u00a76[S1ind APE v3.2.2]\u00a7f " + r1,
            "\u00a7b[S1ind Metrics]\u00a7f " + r2,
            "\u00a77[S1ind Scheduler]\u00a7f " + r3
        );
    }

    private PerformanceTelemetry() {}

    /**
     * Recomputes snapshot strings once per APE cycle off the Render Thread.
     */
    public static void refreshSnapshot() {
        AdaptivePerformanceEngine ape = AdaptivePerformanceEngine.getInstance();
        AdaptiveTaskScheduler sched = AdaptiveTaskScheduler.getInstance();
        TelemetryReadiness readiness = ape.getReadiness();

        String rawLine1;
        if (readiness == TelemetryReadiness.INITIALIZING) {
            rawLine1 = String.format(
                Locale.ROOT,
                "FPS: %.0f (%.1fms) | Status: INITIALIZING (n=%d/%d)",
                ape.getAvgFps(),
                ape.getAvgFrameTimeMs(),
                ape.getSampledFrameCount(),
                AdaptivePerformanceEngine.WARMUP_MIN_FRAMES
            );
        } else {
            rawLine1 = String.format(
                Locale.ROOT,
                "FPS: %.0f (%.1fms) | 1%% Low: %.0f FPS (n=%d) | 0.1%% Low: %.0f FPS (n=%d) [%s]",
                ape.getAvgFps(),
                ape.getAvgFrameTimeMs(),
                ape.getOnePercentLow(),
                ape.getOnePercentSampleSize(),
                ape.getPointOnePercentLow(),
                ape.getPointOnePercentSampleSize(),
                readiness.name()
            );
        }

        String msptPart = ape.isServerTickRecorded()
            ? String.format(Locale.ROOT, "%.1fms", ape.getMspt())
            : "Remote/N/A";

        String rawLine2 = String.format(
            Locale.ROOT,
            "MSPT: %s | GC Cycle: %dms (STW Pause: %s | Max60s: %dms, %d/m) | Stress: %s | Bottleneck: %s (%s)",
            msptPart,
            GcMonitor.getLastCycleDurationMs(),
            GcMonitor.getStwPauseDisplay(),
            GcMonitor.getMaxDuration60s(),
            GcMonitor.getGcCyclesPerMinute(),
            ape.getStressLevel().name(),
            ape.getBottleneckType().name(),
            ape.getBottleneckConfidence().name()
        );

        String rawLine3 = String.format(
            Locale.ROOT,
            "Deferred Attempts: %d | Unique Deferred: %d | Currently Deferred: %d | Rejected: %d",
            sched.getDeferredAttempts(),
            sched.getUniqueDeferredTasks(),
            sched.getCurrentlyDeferredCount(),
            sched.getRejectedCount()
        );

        cachedSnapshot = new TelemetrySnapshot(
            rawLine1,
            rawLine2,
            rawLine3,
            "\u00a76[S1ind APE v3.2.2]\u00a7f " + rawLine1,
            "\u00a7b[S1ind Metrics]\u00a7f " + rawLine2,
            "\u00a77[S1ind Scheduler]\u00a7f " + rawLine3
        );
    }

    public static TelemetrySnapshot getSnapshot() {
        return cachedSnapshot;
    }
}
