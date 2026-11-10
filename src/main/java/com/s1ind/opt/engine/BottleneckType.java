package com.s1ind.opt.engine;

/**
 * Bottleneck classification reported by AdaptivePerformanceEngine.
 */
public enum BottleneckType {
    /** Insufficient telemetry samples (e.g. INITIALIZING or missing both frame/tick data). */
    UNKNOWN,

    /** Integrated or dedicated server tick time (MSPT) exceeds healthy simulation budget. */
    SERVER_TICK_BOUND,

    /** Client render loop frame time exceeds target FPS budget while local logic MSPT is healthy. */
    CLIENT_RENDER_BOUND,

    /** Frequent or sustained Garbage Collector cycles detected within the rolling 60s window. */
    GC_PRESSURE,

    /** Telemetry confirms frame rate and simulation ticks are within healthy bounds. */
    NO_CLEAR_BOTTLENECK
}
