package com.s1ind.opt.config;

/**
 * High-level optimization profiles for S1ind Optimizer.
 */
public enum PerformanceProfile {
    /** Recommended: Balanced optimization with adaptive throttling and safety guards. */
    BALANCED,

    /** Maximum CPU headroom: Aggressive background shedding and lower worker priorities. */
    PERFORMANCE,

    /** Maximum mod compatibility: Minimal interventions, diagnostics only. */
    COMPATIBILITY
}
