package com.s1ind.opt.engine;

/**
 * Runtime stress level evaluated by AdaptivePerformanceEngine (APE) with dwell hysteresis.
 *
 * Client thresholds (relative to effective FPS target ceiling):
 * - NORMAL: Avg FPS >= 85% of target, 1% Low >= 60% of target, MSPT <= 36ms.
 * - MODERATE: Avg FPS < 80% of target OR 1% Low < 58% of target OR MSPT > 36ms.
 * - CRITICAL: Avg FPS < 55% of target OR 1% Low < 36% of target OR MSPT > 46ms.
 *
 * Dedicated Server mode evaluates solely on tick latency (MSPT):
 * - NORMAL: MSPT <= 36ms
 * - MODERATE: MSPT > 36ms
 * - CRITICAL: MSPT > 46ms
 */
public enum StressLevel {
    NORMAL,
    MODERATE,
    CRITICAL
}
