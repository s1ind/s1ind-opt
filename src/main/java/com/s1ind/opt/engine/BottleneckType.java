package com.s1ind.opt.engine;

/**
 * Identifies the suspected bottleneck category observed by the Adaptive Performance Engine.
 *
 *Note: Client render detection measures client render loop frame latency without
 * hardware GPU telemetry (which could indicate shader overhead, VSync, CPU render thread, or GPU load).
 */
public enum BottleneckType {
    /** Frame times and server tick times are within healthy thresholds. */
    BALANCED,

    /** Local server tick MSPT exceeds 38.0ms (CPU simulation bound). */
    CPU_TICK_BOUND,

    /** Client frame time exceeds target frame budget (>22ms) while local tick MSPT is <28ms (or remote). */
    CLIENT_RENDER_BOUND,

    /** Recent Garbage Collector cycle duration exceeded 50ms within the rolling 60s window. */
    GC_BOUND
}
