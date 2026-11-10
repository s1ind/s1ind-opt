package com.s1ind.opt.engine;

/**
 * Statistical readiness of the 20,000-frame rolling buffer.
 */
public enum TelemetryReadiness {
    /** Fewer than 500 frames captured; percentiles are withheld. */
    INITIALIZING,

    /** 500 to 19,999 frames captured; preliminary statistics available with sample size indicator. */
    PARTIAL,

    /** Full 20,000 frames captured; rolling statistics use full 200 (1%) and 20 (0.1%) frame subsets. */
    STABLE
}
