package com.s1ind.opt.mixin.client;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.memory.MemoryCleaner;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client lifecycle optimization:
 * - Elevates Render Thread priority to Thread.MAX_PRIORITY (10) for frame dispatch.
 * - Starts memory watchdog if enabled in config.
 * - Shuts down watchdog on game stop.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "run", at = @At("HEAD"))
    private void s1ind$onClientRun(CallbackInfo ci) {
        if (S1indOptMod.config() != null) {
            if (S1indOptMod.config().boostRenderThreadPriority()) {
                Thread current = Thread.currentThread();
                current.setPriority(Thread.MAX_PRIORITY);
                S1indOptMod.LOG.info("[S1ind] Render Thread priority elevated to MAX_PRIORITY (10)");
            }

            if (S1indOptMod.config().autoMemoryClean()) {
                MemoryCleaner.start();
            }
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void s1ind$onClientStop(CallbackInfo ci) {
        MemoryCleaner.stop();
    }
}
