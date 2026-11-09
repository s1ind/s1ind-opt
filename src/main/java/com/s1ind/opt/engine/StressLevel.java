package com.s1ind.opt.engine;

/**
 * System stress level dynamically computed by AdaptivePerformanceEngine (APE).
 */
public enum StressLevel {
    /** Frame times < 16.7ms (>=60 FPS) and MSPT < 35ms. Full fidelity mode. */
    NORMAL,

    /** Frame times 16.7ms - 25ms (40-60 FPS) or MSPT 35-45ms. Moderate throttling. */
    MODERATE,

    /** Frame times > 25ms (<40 FPS) or MSPT > 45ms. Maximum background shedding. */
    CRITICAL
}
