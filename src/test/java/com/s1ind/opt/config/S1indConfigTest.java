package com.s1ind.opt.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class S1indConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void testValidJsonAndProfileDifferentiation() throws Exception {
        Path cfgPath = tempDir.resolve("s1ind_opt.json");
        String json = """
            {
              "profile": "PERFORMANCE",
              "backgroundThreadCount": 12,
              "passiveMobDistanceThreshold": 40.0
            }
            """;
        Files.writeString(cfgPath, json);

        S1indConfig cfg = S1indConfig.loadFromFile(cfgPath);
        assertEquals(PerformanceProfile.PERFORMANCE, cfg.profile());
        assertEquals(24.0, cfg.passiveMobDistanceThreshold(), "PERFORMANCE caps passive threshold <= 24b");
        assertEquals(4, cfg.computeBackgroundThreadCount(16), "PERFORMANCE MUST cap even manual thread overrides at 4");
        assertEquals(1, cfg.computeBackgroundThreadCount(2), "Dual-core JVM in PERFORMANCE must yield 1 background worker");

        S1indConfig balancedAuto = S1indConfig.parseJsonSafely("{\"profile\":\"BALANCED\"}");
        assertEquals(1, balancedAuto.computeBackgroundThreadCount(2), "Dual-core JVM auto in BALANCED must yield 1 background worker");
        assertEquals(6, balancedAuto.computeBackgroundThreadCount(8), "8-core auto leaves 2 threads headroom");
    }

    @Test
    void testCompatibilityProfilePreservesTelemetryAndDisablesOverrides() {
        String json = """
            {
              "profile": "COMPATIBILITY",
              "backgroundThreadCount": 8
            }
            """;
        S1indConfig cfg = S1indConfig.parseJsonSafely(json);
        assertEquals(PerformanceProfile.COMPATIBILITY, cfg.profile());
        assertTrue(cfg.enableF3Telemetry(), "Compatibility mode MUST NOT turn off F3 telemetry");
        assertFalse(cfg.boostRenderThreadPriority());
        assertFalse(cfg.lowerBackgroundThreadPriority());
        assertFalse(cfg.throttleDistantPassiveMobAi());
        assertFalse(cfg.fastLightUpdates());
        assertEquals(0, cfg.computeBackgroundThreadCount(8), "Compatibility mode must not override Vanilla worker threads");
    }

    @Test
    void testCorruptJsonCreatesBackupAndRecoversSafely() throws Exception {
        Path cfgPath = tempDir.resolve("s1ind_opt.json");
        Files.writeString(cfgPath, "{ broken_json: [123, }");

        S1indConfig cfg = S1indConfig.loadFromFile(cfgPath);
        assertNotNull(cfg);
        assertEquals(PerformanceProfile.BALANCED, cfg.profile());
        assertTrue(Files.exists(tempDir.resolve("s1ind_opt.json.bak")), "Corrupt config must be backed up");
        assertTrue(Files.exists(cfgPath), "Valid default config must be atomically restored");
    }

    @Test
    void testEmptyNullAndMissingFieldsAndUnknownEnumAndClamping() {
        // Empty JSON
        S1indConfig empty = S1indConfig.parseJsonSafely("");
        assertEquals(PerformanceProfile.BALANCED, empty.profile());

        // "null" JSON
        S1indConfig nullCfg = S1indConfig.parseJsonSafely("null");
        assertEquals(PerformanceProfile.BALANCED, nullCfg.profile());

        // Unknown enum + extreme out-of-bounds numbers + missing fields while retaining valid user option
        String weird = """
            {
              "profile": "NON_EXISTENT_ULTRA_PROFILE",
              "enableF3Telemetry": false,
              "backgroundThreadCount": -5,
              "passiveMobDistanceThreshold": 9999.0,
              "memoryCheckIntervalSeconds": 1,
              "memoryPressureThresholdPercent": 200
            }
            """;
        S1indConfig recovered = S1indConfig.parseJsonSafely(weird);
        assertEquals(PerformanceProfile.BALANCED, recovered.profile(), "Unknown enum must default to BALANCED");
        assertFalse(recovered.enableF3Telemetry(), "Valid unrelated user setting must be preserved");
        assertEquals(0, recovered.rawBackgroundThreadCountSetting(), "Negative thread count clamped to 0 (auto)");
        assertEquals(128.0, recovered.passiveMobDistanceThreshold(), "Distance clamped to upper bound 128");
        assertEquals(10, recovered.memoryCheckIntervalSeconds(), "Interval clamped to min 10s");
        assertEquals(95, recovered.memoryPressureThresholdPercent(), "Heap percent clamped to max 95%");
    }
}
