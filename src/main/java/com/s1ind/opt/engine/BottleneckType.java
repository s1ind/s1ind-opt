package com.s1ind.opt.engine;

/**
 * Primary suspected performance bottleneck classification.
 *
 * Notice: Without hardware GPU driver telemetry (NVML/ADL/DXGI), client frame
 * bottlenecks are conservatively labeled CLIENT_RENDER_BOUND or UNKNOWN rather
 * than making unverified hardware claims.
 */
public enum BottleneckType {
    /** Server simulation tick MSPT exceeds healthy thresholds while render frames remain fast. */
    CPU_BOUND,

    /** Client frame durations exceed frame target while server logic ticks remain healthy (or remote). */
    CLIENT_RENDER_BOUND,

    /** Significant Garbage Collector cycles detected within the rolling 60s window. */
    GC_PRESSURE,

    /** System is healthy/balanced or insufficient/ambiguous signals exist to blame a single subsystem. */
    UNKNOWN
}
