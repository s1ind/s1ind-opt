package com.s1ind.opt;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class S1indOptClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        S1indOptMod.LOG.info("[S1ind] Client optimizations loaded. Ready to accelerate FPS alongside Sodium & ImmediatelyFast.");
    }
}
