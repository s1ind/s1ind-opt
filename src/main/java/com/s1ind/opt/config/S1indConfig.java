package com.s1ind.opt.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.s1ind.opt.S1indOptMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod configuration.
 * Persists as JSON in config/s1ind_opt.json.
 */
public class S1indConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Thread & Rendering Settings ──────────────────────────────
    /** Elevates Render Thread priority to Thread.MAX_PRIORITY (10) for frame dispatch. */
    private boolean boostRenderThreadPriority = true;

    /** Background worker threads for Minecraft (0 = auto). */
    private int backgroundThreadCount = 0;

    // ── Entity Settings ──────────────────────────────────────────
    /** Throttles pathfinding updates for PASSIVE mobs (animals, villagers) when >32 blocks from players. */
    private boolean throttleDistantPassiveMobAi = true;

    // ── Lighting Settings ────────────────────────────────────────
    /** Skips lighting update loops when no updates are pending. */
    private boolean fastLightUpdates = true;

    // ── Memory Settings ──────────────────────────────────────────
    /** Checks heap pressure and triggers cleanup only when threshold is exceeded. */
    private boolean autoMemoryClean = true;

    /** Interval in seconds between memory pressure checks (default: 30s). */
    private int memoryCheckIntervalSeconds = 30;

    /** Trigger cleanup when used heap exceeds this percent of max memory (default: 85%). */
    private int memoryPressureThresholdPercent = 85;

    // ── Accessors ────────────────────────────────────────────────

    public boolean boostRenderThreadPriority()       { return boostRenderThreadPriority; }
    public boolean throttleDistantPassiveMobAi()      { return throttleDistantPassiveMobAi; }
    public boolean fastLightUpdates()                { return fastLightUpdates; }
    public boolean autoMemoryClean()                 { return autoMemoryClean; }
    public int     memoryCheckIntervalSeconds()      { return Math.max(5, memoryCheckIntervalSeconds); }
    public int     memoryPressureThresholdPercent()  { return Math.min(95, Math.max(50, memoryPressureThresholdPercent)); }

    public int backgroundThreadCount() {
        if (backgroundThreadCount <= 0) {
            return Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        }
        return Math.min(backgroundThreadCount, Runtime.getRuntime().availableProcessors());
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
