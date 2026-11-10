package com.s1ind.opt.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.s1ind.opt.S1indOptMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for S1ind Optimizer v3.1.0.
 * Persists as JSON in config/s1ind_opt.json.
 */
public class S1indConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Profile ──────────────────────────────────────────────────
    private PerformanceProfile profile = PerformanceProfile.BALANCED;

    // ── Adaptive Performance Engine (APE) ─────────────────────────
    /** Dynamically monitors frame jitter, 1% / 0.1% Low FPS, and scales throttling. */
    private boolean enableAdaptiveEngine = true;

    /** Shows real-time metrics (1% Low, 0.1% Low, MSPT, GC) on F3 debug screen. */
    private boolean enableF3Telemetry = true;

    // ── Thread & Scheduling Settings ─────────────────────────────
    /** Requests OS scheduler bias for Render Thread via Thread.MAX_PRIORITY (10). */
    private boolean boostRenderThreadPriority = true;

    /** Lowers background worker threads (ForkJoin & IO) to Thread.NORM_PRIORITY - 1 (4). */
    private boolean lowerBackgroundThreadPriority = true;

    /** Background worker thread limit (0 = auto-calculate). */
    private int backgroundThreadCount = 0;

    // ── Safe AI Scheduler Settings ───────────────────────────────
    /** Heuristic throttling for distant passive mobs beyond player radius. */
    private boolean throttleDistantPassiveMobAi = true;

    /** Protects villagers and wandering traders from AI throttling (preserves iron farms/trades). */
    private boolean protectVillagerAi = true;

    /** Protects third-party modded entities from AI throttling. */
    private boolean protectModdedEntities = true;

    /** Distance in blocks beyond which passive mobs receive tiered throttling. */
    private double passiveMobDistanceThreshold = 32.0;

    // ── Lighting Settings ────────────────────────────────────────
    /** Early-exit fast-path for LightingProvider when no lighting updates are queued. */
    private boolean fastLightUpdates = true;

    // ── Memory Settings ──────────────────────────────────────────
    /** Emergency memory watchdog (disabled by default, recommended for <=3GB heap). */
    private boolean autoMemoryClean = false;

    /** Interval in seconds between memory pressure checks (default: 30s). */
    private int memoryCheckIntervalSeconds = 30;

    /** Trigger cleanup only when used heap exceeds this percent of max memory (default: 88%). */
    private int memoryPressureThresholdPercent = 88;

    // ── Accessors ────────────────────────────────────────────────

    public PerformanceProfile profile()                  { return profile != null ? profile : PerformanceProfile.BALANCED; }
    public boolean enableAdaptiveEngine()               { return profile != PerformanceProfile.COMPATIBILITY && enableAdaptiveEngine; }
    public boolean enableF3Telemetry()                  { return enableF3Telemetry; }
    public boolean boostRenderThreadPriority()          { return profile != PerformanceProfile.COMPATIBILITY && boostRenderThreadPriority; }
    public boolean lowerBackgroundThreadPriority()      { return profile != PerformanceProfile.COMPATIBILITY && lowerBackgroundThreadPriority; }
    public boolean throttleDistantPassiveMobAi()         { return profile != PerformanceProfile.COMPATIBILITY && throttleDistantPassiveMobAi; }
    public boolean protectVillagerAi()                  { return protectVillagerAi; }
    public boolean protectModdedEntities()              { return protectModdedEntities; }
    public double  passiveMobDistanceThreshold()         { return Math.max(16.0, Math.min(128.0, passiveMobDistanceThreshold)); }
    public boolean fastLightUpdates()                   { return profile != PerformanceProfile.COMPATIBILITY && fastLightUpdates; }
    public boolean autoMemoryClean()                    { return autoMemoryClean; }
    public int     memoryCheckIntervalSeconds()         { return Math.max(10, memoryCheckIntervalSeconds); }
    public int     memoryPressureThresholdPercent()     { return Math.min(95, Math.max(70, memoryPressureThresholdPercent)); }

    /**
     * Budgets background worker threads based on available logical cores:
     * - Dual-core (<=2 cores): 1 worker
     * - Quad-core (3-4 cores): cores - 1
     * - Multi-core (5+ cores): cores - 2, capped at 8
     */
    public int backgroundThreadCount() {
        int availableCores = Runtime.getRuntime().availableProcessors();
        if (backgroundThreadCount <= 0) {
            if (availableCores <= 2) {
                return 1;
            } else if (availableCores <= 4) {
                return availableCores - 1;
            } else {
                return Math.min(availableCores - 2, 8);
            }
        }
        return Math.min(backgroundThreadCount, availableCores);
    }

    // ── Persistence ──────────────────────────────────────────────

    public static S1indConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                S1indConfig cfg = GSON.fromJson(json, S1indConfig.class);
                if (cfg != null) {
                    cfg.save();
                    return cfg;
                }
            } catch (IOException e) {
                S1indOptMod.LOG.warn("[S1ind] Failed to read config, using defaults", e);
            }
        }
        S1indConfig cfg = new S1indConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            S1indOptMod.LOG.error("[S1ind] Failed to save config", e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("s1ind_opt.json");
    }
}
