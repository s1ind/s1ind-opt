package com.s1ind.opt.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.engine.AdaptiveTaskScheduler;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.SubmissionResult;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.SubmissionStatus;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskPriority;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * Configuration manager for S1ind Optimizer v3.2.2.
 *
 * Reliability guarantees:
 * - Immutable ConfigSnapshot captured synchronously before asynchronous submission.
 * - Serialized atomic file writes using unique per-operation temp files (zero .tmp collision).
 * - Safe fallback to StandardCopyOption.REPLACE_EXISTING if ATOMIC_MOVE is unsupported.
 * - Explicit SaveStatus reported asynchronously via CompletableFuture (detects scheduler rejection/errors).
 */
public class S1indConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object FILE_WRITE_LOCK = new Object();

    public enum SaveStatus {
        SAVED,
        REJECTED_BY_SCHEDULER,
        WRITE_FAILED
    }

    /** Immutable configuration snapshot guaranteed safe from post-submit mutations. */
    public record ConfigSnapshot(
        PerformanceProfile profile,
        boolean enableAdaptiveEngine,
        boolean enableF3Telemetry,
        boolean boostRenderThreadPriority,
        boolean lowerBackgroundThreadPriority,
        int backgroundThreadCount,
        boolean throttleDistantPassiveMobAi,
        boolean protectVillagerAi,
        boolean protectModdedEntities,
        double passiveMobDistanceThreshold,
        boolean fastLightUpdates,
        boolean autoMemoryClean,
        int memoryCheckIntervalSeconds,
        int memoryPressureThresholdPercent
    ) {}

    // ── Profile ──────────────────────────────────────────────────
    private PerformanceProfile profile = PerformanceProfile.BALANCED;

    // ── Adaptive Performance Engine (APE) ─────────────────────────
    private boolean enableAdaptiveEngine = true;
    private boolean enableF3Telemetry = true;

    // ── Thread & Scheduling Settings ─────────────────────────────
    private boolean boostRenderThreadPriority = true;
    private boolean lowerBackgroundThreadPriority = true;
    private int backgroundThreadCount = 0;

    // ── Safe AI Scheduler Settings ───────────────────────────────
    private boolean throttleDistantPassiveMobAi = true;
    private boolean protectVillagerAi = true;
    private boolean protectModdedEntities = true;
    private double passiveMobDistanceThreshold = 32.0;

    // ── Lighting Settings ────────────────────────────────────────
    private boolean fastLightUpdates = true;

    // ── Memory Diagnostics Settings ──────────────────────────────
    private boolean autoMemoryClean = false;
    private int memoryCheckIntervalSeconds = 30;
    private int memoryPressureThresholdPercent = 88;

    // ── Accessors & Mutators ─────────────────────────────────────

    public PerformanceProfile profile()                  { return profile != null ? profile : PerformanceProfile.BALANCED; }
    public boolean enableAdaptiveEngine()               { return profile() != PerformanceProfile.COMPATIBILITY && enableAdaptiveEngine; }
    public boolean enableF3Telemetry()                  { return enableF3Telemetry; }
    public boolean boostRenderThreadPriority()          { return profile() != PerformanceProfile.COMPATIBILITY && boostRenderThreadPriority; }
    public boolean lowerBackgroundThreadPriority()      { return profile() != PerformanceProfile.COMPATIBILITY && lowerBackgroundThreadPriority; }
    public boolean throttleDistantPassiveMobAi()         { return profile() != PerformanceProfile.COMPATIBILITY && throttleDistantPassiveMobAi; }
    public boolean protectVillagerAi()                  { return protectVillagerAi; }
    public boolean protectModdedEntities()              { return protectModdedEntities; }
    public boolean fastLightUpdates()                   { return profile() != PerformanceProfile.COMPATIBILITY && fastLightUpdates; }
    public boolean autoMemoryClean()                    { return autoMemoryClean; }
    public int     memoryCheckIntervalSeconds()         { return Math.max(10, Math.min(300, memoryCheckIntervalSeconds)); }
    public int     memoryPressureThresholdPercent()     { return Math.min(95, Math.max(70, memoryPressureThresholdPercent)); }
    public int     rawBackgroundThreadCountSetting()    { return backgroundThreadCount; }

    public void setProfile(PerformanceProfile profile)                 { this.profile = profile; sanitize(); }
    public void setEnableF3Telemetry(boolean enabled)                  { this.enableF3Telemetry = enabled; }
    public void setBackgroundThreadCount(int count)                    { this.backgroundThreadCount = count; sanitize(); }
    public void setPassiveMobDistanceThreshold(double threshold)       { this.passiveMobDistanceThreshold = threshold; sanitize(); }

    public double passiveMobDistanceThreshold() {
        double configured = sanitizeThreshold(passiveMobDistanceThreshold);
        if (profile() == PerformanceProfile.PERFORMANCE) {
            return Math.min(configured, 24.0);
        }
        return configured;
    }

    public int backgroundThreadCount() {
        return computeBackgroundThreadCount(Runtime.getRuntime().availableProcessors());
    }

    public int computeBackgroundThreadCount(int logicalProcessors) {
        int procs = Math.max(1, logicalProcessors);
        if (profile() == PerformanceProfile.COMPATIBILITY) {
            return 0;
        }

        int resolved;
        if (backgroundThreadCount <= 0) {
            if (procs <= 2) {
                resolved = 1;
            } else if (procs <= 4) {
                resolved = procs - 1;
            } else {
                resolved = Math.min(procs - 2, 8);
            }
        } else {
            resolved = Math.min(backgroundThreadCount, procs);
        }

        if (profile() == PerformanceProfile.PERFORMANCE) {
            if (procs <= 2) {
                return 1;
            }
            return Math.min(resolved, 4);
        }
        return Math.max(1, resolved);
    }

    public synchronized ConfigSnapshot createImmutableSnapshot() {
        sanitize();
        return new ConfigSnapshot(
            this.profile(),
            this.enableAdaptiveEngine,
            this.enableF3Telemetry,
            this.boostRenderThreadPriority,
            this.lowerBackgroundThreadPriority,
            this.backgroundThreadCount,
            this.throttleDistantPassiveMobAi,
            this.protectVillagerAi,
            this.protectModdedEntities,
            this.passiveMobDistanceThreshold,
            this.fastLightUpdates,
            this.autoMemoryClean,
            this.memoryCheckIntervalSeconds,
            this.memoryPressureThresholdPercent
        );
    }

    // ── Sanitization ─────────────────────────────────────────────

    public synchronized void sanitize() {
        if (this.profile == null) {
            this.profile = PerformanceProfile.BALANCED;
        }
        if (this.backgroundThreadCount < 0) {
            this.backgroundThreadCount = 0;
        }
        this.passiveMobDistanceThreshold = sanitizeThreshold(this.passiveMobDistanceThreshold);
        this.memoryCheckIntervalSeconds = Math.max(10, Math.min(300, this.memoryCheckIntervalSeconds));
        this.memoryPressureThresholdPercent = Math.min(95, Math.max(70, this.memoryPressureThresholdPercent));
    }

    private static double sanitizeThreshold(double raw) {
        if (!Double.isFinite(raw)) {
            return 32.0;
        }
        return Math.max(18.0, Math.min(128.0, raw));
    }

    // ── Persistence & Recovery ───────────────────────────────────

    public static S1indConfig parseJsonSafely(String json) {
        if (json == null || json.isBlank()) {
            return new S1indConfig();
        }
        S1indConfig cfg = GSON.fromJson(json, S1indConfig.class);
        if (cfg == null) {
            return new S1indConfig();
        }
        cfg.sanitize();
        return cfg;
    }

    public static S1indConfig load() {
        return loadFromFile(configPath());
    }

    public static S1indConfig loadFromFile(Path path) {
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                S1indConfig cfg = parseJsonSafely(json);
                if (json == null || json.isBlank() || "null".equals(json.trim())) {
                    saveSnapshotAtomically(cfg.createImmutableSnapshot(), path);
                }
                return cfg;
            } catch (IOException | JsonParseException | IllegalStateException e) {
                S1indOptMod.LOG.warn("[S1ind] Corrupt or unreadable config at {}, backing up to .bak and resetting defaults", path, e);
                try {
                    Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException backupErr) {
                    S1indOptMod.LOG.warn("[S1ind] Failed backing up corrupt config", backupErr);
                }
            }
        }
        S1indConfig cfg = new S1indConfig();
        saveSnapshotAtomically(cfg.createImmutableSnapshot(), path);
        return cfg;
    }

    public SaveStatus save() {
        return saveSnapshotAtomically(createImmutableSnapshot(), configPath());
    }

    public CompletableFuture<SaveStatus> saveAsync() {
        return saveAsyncToFile(configPath());
    }

    /**
     * Captures an immutable ConfigSnapshot synchronously and schedules serialized atomic write.
     * Never blocks the Render Thread and explicitly informs caller if Scheduler rejected the request.
     */
    public CompletableFuture<SaveStatus> saveAsyncToFile(Path path) {
        final ConfigSnapshot snapshot = createImmutableSnapshot();
        final CompletableFuture<SaveStatus> future = new CompletableFuture<>();

        SubmissionResult sub = AdaptiveTaskScheduler.getInstance().submit(
            () -> {
                SaveStatus result = saveSnapshotAtomically(snapshot, path);
                future.complete(result);
            },
            TaskPriority.NORMAL,
            "config-save"
        );

        if (sub.status() == SubmissionStatus.REJECTED) {
            S1indOptMod.LOG.warn("[S1ind] Async config save rejected by AdaptiveTaskScheduler for path {}", path);
            future.complete(SaveStatus.REJECTED_BY_SCHEDULER);
        }

        return future;
    }

    public static SaveStatus saveSnapshotAtomically(ConfigSnapshot snapshot, Path path) {
        synchronized (FILE_WRITE_LOCK) {
            Path tmp = null;
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                    tmp = Files.createTempFile(parent, path.getFileName() + ".", ".tmp");
                } else {
                    tmp = Files.createTempFile(path.getFileName() + ".", ".tmp");
                }

                Files.writeString(tmp, GSON.toJson(snapshot));
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                return SaveStatus.SAVED;
            } catch (IOException e) {
                S1indOptMod.LOG.error("[S1ind] Failed saving config atomically to {}", path, e);
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException cleanupErr) {
                        S1indOptMod.LOG.warn("[S1ind] Failed removing stale temp config file {}", tmp, cleanupErr);
                    }
                }
                return SaveStatus.WRITE_FAILED;
            }
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("s1ind_opt.json");
    }
}
