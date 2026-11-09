package com.s1ind.opt.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.s1ind.opt.S1indOptMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for S1ind Optimizer v2.5.0.
 * Persists as JSON in config/s1ind_opt.json.
 */
public class S1indConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Thread & Rendering Settings ──────────────────────────────
    /**
     * Elevates the Render Thread priority to Thread.MAX_PRIORITY (10).
     * Gives OS scheduler priority bias to frame rendering over background tasks.
     */
    private boolean boostRenderThreadPriority = true;

    /**
     * Lowers background worker threads (ForkJoin & IO) to Thread.NORM_PRIORITY - 1 (4).
     */
    private boolean lowerBackgroundThreadPriority = true;

    /**
     * Background worker thread limit for Minecraft (0 = auto-calculate).
     */
    private int backgroundThreadCount = 0;

    // ── Entity Settings ──────────────────────────────────────────
    /**
     * Throttles AI pathfinding updates strictly for PASSIVE mobs (animals, villagers)
     * when they are farther than 32 blocks from players.
     */
    private boolean throttleDistantPassiveMobAi = true;

    // ── Lighting Settings ────────────────────────────────────────
    /**
     * Early-exit fast-path for LightingProvider when no lighting updates are queued.
     */
    private boolean fastLightUpdates = true;

    // ── Memory Settings ──────────────────────────────────────────
    /**
     * Background memory watchdog.
     * Disabled by default: Recommended only for memory-constrained setups (<=3 GB heap).
     */
    private boolean autoMemoryClean = false;

    /** Interval in seconds between memory pressure checks (default: 30s). */
    private int memoryCheckIntervalSeconds = 30;

    /** Trigger cleanup only when used heap exceeds this percent of max memory (default: 88%). */
    private int memoryPressureThresholdPercent = 88;

    // ── Accessors ────────────────────────────────────────────────

    public boolean boostRenderThreadPriority()       { return boostRenderThreadPriority; }
    public boolean lowerBackgroundThreadPriority()   { return lowerBackgroundThreadPriority; }
    public boolean throttleDistantPassiveMobAi()      { return throttleDistantPassiveMobAi; }
    public boolean fastLightUpdates()                { return fastLightUpdates; }
    public boolean autoMemoryClean()                 { return autoMemoryClean; }
    public int     memoryCheckIntervalSeconds()      { return Math.max(10, memoryCheckIntervalSeconds); }
    public int     memoryPressureThresholdPercent()  { return Math.min(95, Math.max(70, memoryPressureThresholdPercent)); }

    /**
     * Intelligently budgets background worker threads based on available CPU cores:
     * - Dual-core (<=2 cores): 1 worker (leaves 1 dedicated core for Game / Render)
     * - Quad-core (3-4 cores): cores - 1 (leaves 1 core free)
     * - Multi-core (5+ cores): cores - 2, capped at 8 (leaves 2 cores for Game loop + Render)
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
