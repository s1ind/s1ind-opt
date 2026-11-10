package com.s1ind.opt.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.engine.AdaptiveTaskScheduler;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskPriority;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Configuration manager for S1ind Optimizer v3.2.1.
 *
 * Reliability guarantees:
 * - Corrupt, empty, or malformed JSON never crashes startup; backs up corrupt file and recovers safely.
 * - Unknown enum values fall back gracefully without discarding other valid user settings.
 * - Strict bounds clamping on all numeric fields.
 * - Enforces profile ceilings (e.g., PERFORMANCE <=4 background workers) over manual overrides.
 * - Atomic save semantics and async disk writes so Render Thread avoids I/O blocking.
 */
public class S1indConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Profile ──────────────────────────────────────────────────
    private PerformanceProfile profile = PerformanceProfile.BALANCED;

    // ── Adaptive Performance Engine (APE) ─────────────────────────
    /** Enables adaptive load shedding based on frame / MSPT stress. Telemetry runs regardless. */
    private boolean enableAdaptiveEngine = true;

    /** Shows live metrics (1% Low, 0.1% Low, MSPT, GC) on F3 debug screen. */
    private boolean enableF3Telemetry = true;

    // ── Thread & Scheduling Settings ─────────────────────────────
    /** Requests OS scheduler bias for Render Thread via Thread.MAX_PRIORITY (10). */
    private boolean boostRenderThreadPriority = true;

    /** Lowers Minecraft worker threads (ForkJoin & IO) to Thread.NORM_PRIORITY - 1 (4). */
    private boolean lowerBackgroundThreadPriority = true;

    /** Background worker limit (0 = auto-calculate based on profile and JVM logical processors). */
    private int backgroundThreadCount = 0;

    // ── Safe AI Scheduler Settings ───────────────────────────────
    /** Heuristic throttling for distant passive mobs beyond player radius. */
    private boolean throttleDistantPassiveMobAi = true;

    /** Protects villagers and wandering traders from AI throttling. */
    private boolean protectVillagerAi = true;

    /** Protects third-party modded entities from AI throttling. */
    private boolean protectModdedEntities = true;

    /** Distance in blocks beyond which distant passive mobs receive tiered throttling. */
    private double passiveMobDistanceThreshold = 32.0;

    // ── Lighting Settings ────────────────────────────────────────
    /** Early-exit fast-path for LightingProvider when no lighting updates are queued. */
    private boolean fastLightUpdates = true;

    // ── Memory Settings ──────────────────────────────────────────
    /** Diagnostic-only advisory memory watchdog (explicitly OFF by default). */
    private boolean autoMemoryClean = false;

    /** Interval in seconds between watchdog checks when enabled (10-300s). */
    private int memoryCheckIntervalSeconds = 30;

    /** Trigger advisory GC only when used heap exceeds threshold and free headroom <250MB (70-95%). */
    private int memoryPressureThresholdPercent = 88;

    // ── Accessors ────────────────────────────────────────────────

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

    public int rawBackgroundThreadCountSetting()        { return backgroundThreadCount; }

    public double passiveMobDistanceThreshold() {
        double configured = sanitizeThreshold(passiveMobDistanceThreshold);
        if (profile() == PerformanceProfile.PERFORMANCE) {
            return Math.min(configured, 24.0);
        }
        return configured;
    }

    /**
     * Computes background worker count from logical processors exposed to JVM
     * (Runtime.getRuntime().availableProcessors()).
     *
     * Rules:
     * - COMPATIBILITY: returns 0 (does not override Vanilla thread count).
     * - PERFORMANCE: hard cap of 4 workers enforced on BOTH auto and manual configurations.
     * - BALANCED: dual-core JVM -> 1 worker; 3-4 -> n-1; 5+ -> min(n-2, 8).
     */
    public int backgroundThreadCount() {
        return computeBackgroundThreadCount(Runtime.getRuntime().availableProcessors());
    }

    /** Testable pure method taking logical processor count. */
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

    // ── Sanitization ─────────────────────────────────────────────

    public void sanitize() {
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
                    saveAtomically(cfg, path);
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
        saveAtomically(cfg, path);
        return cfg;
    }

    public void save() {
        saveAtomically(this, configPath());
    }

    public void saveAsync() {
        final S1indConfig snapshot = this;
        final Path path = configPath();
        AdaptiveTaskScheduler.getInstance().submit(
            () -> saveAtomically(snapshot, path),
            TaskPriority.NORMAL,
            "config-save"
        );
    }

    public static void saveAtomically(S1indConfig cfg, Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(cfg));
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            S1indOptMod.LOG.error("[S1ind] Failed to save config atomically to {}", path, e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("s1ind_opt.json");
    }
}
