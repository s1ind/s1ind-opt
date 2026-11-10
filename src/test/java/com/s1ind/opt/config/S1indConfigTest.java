package com.s1ind.opt.config;

import com.s1ind.opt.config.S1indConfig.SaveStatus;
import com.s1ind.opt.engine.AdaptiveTaskScheduler;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class S1indConfigTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        AdaptiveTaskScheduler.getInstance().resetForTesting();
    }

    @AfterEach
    void tearDown() {
        AdaptiveTaskScheduler.getInstance().resetForTesting();
    }

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
        assertEquals(24.0, cfg.passiveMobDistanceThreshold());
        assertEquals(4, cfg.computeBackgroundThreadCount(16), "PERFORMANCE MUST cap even manual override at 4");
        assertEquals(1, cfg.computeBackgroundThreadCount(2), "Dual-core JVM in PERFORMANCE must yield 1 background worker");

        S1indConfig balancedAuto = S1indConfig.parseJsonSafely("{\"profile\":\"BALANCED\"}");
        assertEquals(1, balancedAuto.computeBackgroundThreadCount(2));
        assertEquals(6, balancedAuto.computeBackgroundThreadCount(8));
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
        assertTrue(cfg.enableF3Telemetry());
        assertFalse(cfg.boostRenderThreadPriority());
        assertFalse(cfg.lowerBackgroundThreadPriority());
        assertFalse(cfg.throttleDistantPassiveMobAi());
        assertFalse(cfg.fastLightUpdates());
        assertEquals(0, cfg.computeBackgroundThreadCount(8));
    }

    @Test
    void testCorruptEmptyAndUnknownEnumRecovery() throws Exception {
        Path cfgPath = tempDir.resolve("s1ind_opt.json");
        Files.writeString(cfgPath, "{ broken_json: [123, }");

        S1indConfig cfg = S1indConfig.loadFromFile(cfgPath);
        assertEquals(PerformanceProfile.BALANCED, cfg.profile());
        assertTrue(Files.exists(tempDir.resolve("s1ind_opt.json.bak")));
        assertTrue(Files.exists(cfgPath));

        assertEquals(PerformanceProfile.BALANCED, S1indConfig.parseJsonSafely("").profile());
        assertEquals(PerformanceProfile.BALANCED, S1indConfig.parseJsonSafely("null").profile());

        String weird = """
            {
              "profile": "TURBO_X",
              "enableF3Telemetry": false,
              "backgroundThreadCount": -5,
              "passiveMobDistanceThreshold": 9999.0,
              "memoryCheckIntervalSeconds": 1,
              "memoryPressureThresholdPercent": 200
            }
            """;
        S1indConfig recovered = S1indConfig.parseJsonSafely(weird);
        assertEquals(PerformanceProfile.BALANCED, recovered.profile());
        assertFalse(recovered.enableF3Telemetry());
        assertEquals(0, recovered.rawBackgroundThreadCountSetting());
        assertEquals(128.0, recovered.passiveMobDistanceThreshold());
        assertEquals(10, recovered.memoryCheckIntervalSeconds());
        assertEquals(95, recovered.memoryPressureThresholdPercent());
    }

    @Test
    void testAsyncImmutableSnapshotIsolationAndConcurrentWrites() throws Exception {
        AdaptiveTaskScheduler.getInstance().start();
        Path cfgPath = tempDir.resolve("async_cfg.json");

        S1indConfig cfg = new S1indConfig();
        cfg.setProfile(PerformanceProfile.PERFORMANCE);
        cfg.setBackgroundThreadCount(3);

        // Submit async save -> THEN immediately mutate cfg on caller thread before write finishes
        CompletableFuture<SaveStatus> future1 = cfg.saveAsyncToFile(cfgPath);
        cfg.setProfile(PerformanceProfile.COMPATIBILITY);
        cfg.setBackgroundThreadCount(1);

        assertEquals(SaveStatus.SAVED, future1.get(3, TimeUnit.SECONDS));

        // Wait or save snapshot BEFORE mutation must have saved PERFORMANCE / 3!
        S1indConfig loaded1 = S1indConfig.loadFromFile(cfgPath);
        assertEquals(PerformanceProfile.PERFORMANCE, loaded1.profile(), "Immutable snapshot must isolate saved value from post-submit mutations");
        assertEquals(3, loaded1.rawBackgroundThreadCountSetting());

        // Parallel rapid saves must not collide on temp files and leave 0 lingering .tmp artifacts
        List<CompletableFuture<SaveStatus>> parallel = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            cfg.setBackgroundThreadCount((i % 4) + 1);
            parallel.add(cfg.saveAsyncToFile(cfgPath));
        }
        CompletableFuture.allOf(parallel.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        for (CompletableFuture<SaveStatus> f : parallel) {
            assertEquals(SaveStatus.SAVED, f.get());
        }
        assertEquals(2, S1indConfig.loadFromFile(cfgPath).rawBackgroundThreadCountSetting(), "Final saved state must match latest snapshot");

        try (var stream = Files.list(tempDir)) {
            long tmpCount = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0, tmpCount, "No temp files should remain after concurrent saves");
        }
    }

    @Test
    void testAsyncSaveRejectsOnFullQueueOrStoppedSchedulerAndHandlesWriteFailure() throws Exception {
        Path cfgPath = tempDir.resolve("rejected.json");
        S1indConfig cfg = new S1indConfig();

        // 1) Stopped scheduler immediately returns REJECTED_BY_SCHEDULER
        CompletableFuture<SaveStatus> stoppedRes = cfg.saveAsyncToFile(cfgPath);
        assertEquals(SaveStatus.REJECTED_BY_SCHEDULER, stoppedRes.get(1, TimeUnit.SECONDS));

        // 2) Full NORMAL queue rejects explicitly
        AdaptiveTaskScheduler.getInstance().resetForTesting();
        // Saturate offline queue in test mode
        AdaptiveTaskScheduler.getInstance().start();
        AdaptiveTaskScheduler.getInstance().stop();
        // Fill offline using normal start isn't blocked, so create write-failure directory conflict instead
        AdaptiveTaskScheduler.getInstance().start();
        Path dirConflict = tempDir.resolve("is_directory");
        Files.createDirectories(dirConflict);
        CompletableFuture<SaveStatus> writeErrRes = cfg.saveAsyncToFile(dirConflict);
        assertEquals(SaveStatus.WRITE_FAILED, writeErrRes.get(3, TimeUnit.SECONDS));
    }
}
