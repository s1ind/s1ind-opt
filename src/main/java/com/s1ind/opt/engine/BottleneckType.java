package com.s1ind.opt.engine;

/**
 * Identifies the primary bottleneck detected by the Adaptive Performance Engine.
 */
public enum BottleneckType {
    /** Frame rate and tick time are healthy. */
    BALANCED,

    /** Server logic thread MSPT exceeds threshold (>40ms) while client render is responsive. */
    CPU_TICK_BOUND,

    /** Client frame time is slow (>22ms) while server MSPT is healthy (<25ms). */
    RENDER_GPU_BOUND,

    /** Significant Stop-The-World GC pause occurred (>50ms) recently. */
    GC_BOUND
}
