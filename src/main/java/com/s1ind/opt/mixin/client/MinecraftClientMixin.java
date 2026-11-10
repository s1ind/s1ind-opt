package com.s1ind.opt.mixin.client;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.engine.AdaptivePerformanceEngine;
import com.s1ind.opt.memory.MemoryCleaner;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client lifecycle and nanosecond frame timing:
 * - Starts background telemetry daemon (isolates percentile math from Render Thread).
 * - Elevates Render Thread priority to MAX_PRIORITY (10) if configured.
 * - Measures render() duration in O(1) for AdaptivePerformanceEngine.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Unique
    private long s1ind$frameStartNano = 0;

    @Inject(method = "run", at = @At("HEAD"))
    private void s1ind$onClientRun(CallbackInfo ci) {
        AdaptivePerformanceEngine.getInstance().startBackgroundWorker();

        if (S1indOptMod.config() != null) {
            if (S1indOptMod.config().boostRenderThreadPriority()) {
                Thread current = Thread.currentThread();
                current.setPriority(Thread.MAX_PRIORITY);
                S1indOptMod.LOG.info("[S1ind] Render Thread priority set to MAX_PRIORITY (10)");
            }

            if (S1indOptMod.config().autoMemoryClean()) {
                MemoryCleaner.start();
            }
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void s1ind$beforeRender(boolean tick, CallbackInfo ci) {
        s1ind$frameStartNano = System.nanoTime();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void s1ind$afterRender(boolean tick, CallbackInfo ci) {
        if (s1ind$frameStartNano > 0) {
            long duration = System.nanoTime() - s1ind$frameStartNano;
            AdaptivePerformanceEngine.getInstance().recordFrame(duration);
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void s1ind$onClientStop(CallbackInfo ci) {
        AdaptivePerformanceEngine.getInstance().stopBackgroundWorker();
        MemoryCleaner.stop();
    }
}
