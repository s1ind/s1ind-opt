package com.s1ind.opt;

import com.s1ind.opt.config.S1indConfig;
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

        LOG.info("[S1ind] Initializing S1ind Optimizer v2.5.0");
        LOG.info("[S1ind] Render Thread Priority: {}", config.boostRenderThreadPriority() ? "MAX (10)" : "DEFAULT (5)");
        LOG.info("[S1ind] Worker Thread Priority: {}", config.lowerBackgroundThreadPriority() ? "NORM-1 (4)" : "DEFAULT (5)");
        LOG.info("[S1ind] Background Worker Thread Budget: {} threads", config.backgroundThreadCount());
        LOG.info("[S1ind] Distant Passive Mob AI Throttling: {}", config.throttleDistantPassiveMobAi() ? "ON" : "OFF");
        LOG.info("[S1ind] Light Update Early Exit: {}", config.fastLightUpdates() ? "ON" : "OFF");
        LOG.info("[S1ind] Memory Pressure Watchdog: {}", config.autoMemoryClean() ? "ON (Threshold: " + config.memoryPressureThresholdPercent() + "%)" : "OFF (default)");

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER && config.autoMemoryClean()) {
            MemoryCleaner.start();
        }
    }

    public static S1indConfig config() {
        return config;
    }
}
