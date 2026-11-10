package com.s1ind.opt.mixin.client;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.engine.AdaptivePerformanceEngine;
import com.s1ind.opt.memory.MemoryCleaner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client lifecycle and nanosecond frame timing:
 * - Always measures render() duration in O(1) for AdaptivePerformanceEngine (even in Compatibility profile).
 * - Elevates Render Thread priority to MAX_PRIORITY (10) when configured.
 * - Tracks client FPS cap to prevent false CRITICAL triggers on intentional frame limits.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Shadow
    @Final
    public GameOptions options;

    @Unique
    private long s1ind$frameStartNano = 0;

    @Unique
    private int s1ind$capSyncCounter = 0;

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

        if ((++s1ind$capSyncCounter & 127) == 0 && this.options != null) {
            Integer cap = this.options.getMaxFps().getValue();
            if (cap != null) {
                AdaptivePerformanceEngine.getInstance().updateClientFpsLimit(cap);
            }
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void s1ind$onClientStop(CallbackInfo ci) {
        AdaptivePerformanceEngine.getInstance().stopBackgroundWorker();
        MemoryCleaner.stop();
    }
}
