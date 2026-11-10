package com.s1ind.opt.engine;

/**
 * Runtime stress level evaluated by AdaptivePerformanceEngine (APE) with monotonic hysteresis.
 *
 * Evaluation modes:
 * - Dedicated Server (headless, no frames recorded): based strictly on server tick MSPT
 *   (NORMAL <=36ms, MODERATE >36ms, CRITICAL >46ms).
 * - Client: combines local MSPT (if integrated server is running) and frame pacing relative
 *   to configured FPS Cap / VSync without inventing a fixed 60 FPS cap when FPS cap is higher.
 */
public enum StressLevel {
    NORMAL,
    MODERATE,
    CRITICAL
}
