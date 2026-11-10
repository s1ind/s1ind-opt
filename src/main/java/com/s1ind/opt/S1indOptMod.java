package com.s1ind.opt;

import com.s1ind.opt.config.S1indConfig;
import com.s1ind.opt.engine.AdaptivePerformanceEngine;
import com.s1ind.opt.memory.MemoryCleaner;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class S1indOptMod implements ModInitializer {

    public static final String MOD_ID = "s1ind_opt";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static S1indConfig config;

    @Override
    public void onInitialize() {
        config = S1indConfig.load();

        LOG.info("[S1ind] Initializing S1ind Optimizer v3.2.2 (Adaptive Performance Engine)");
        LOG.info("[S1ind] Active Profile: {}", config.profile());
        LOG.info("[S1ind] Adaptive Performance Engine: {}", config.enableAdaptiveEngine() ? "ACTIVE" : "MONITOR_ONLY");
        LOG.info("[S1ind] In-game F3 Telemetry: {}", config.enableF3Telemetry() ? "ENABLED" : "DISABLED");
        LOG.info("[S1ind] Thread Scheduler Overrides: Render={}, Workers={}, Budget={}",
            config.boostRenderThreadPriority() ? "MAX (10)" : "VANILLA",
            config.lowerBackgroundThreadPriority() ? "NORM-1 (4)" : "VANILLA",
            config.backgroundThreadCount() > 0 ? config.backgroundThreadCount() + " threads" : "VANILLA"
        );
        LOG.info("[S1ind] Passive Mob AI Policy: {}",
            config.throttleDistantPassiveMobAi()
                ? "ACTIVE (threshold=" + config.passiveMobDistanceThreshold() + "b, villagersProtected=" + config.protectVillagerAi() + ", moddedProtected=" + config.protectModdedEntities() + ")"
                : "DISABLED"
        );
        LOG.info("[S1ind] Light Fast-Path: {}", config.fastLightUpdates() ? "ENABLED" : "DISABLED");

        // Start APE worker and AdaptiveTaskScheduler on ALL environments (Client & Dedicated Server)
        AdaptivePerformanceEngine.getInstance().startBackgroundWorker();

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER && config.autoMemoryClean()) {
            MemoryCleaner.start();
        }
    }

    public static S1indConfig config() {
        return config;
    }
}
