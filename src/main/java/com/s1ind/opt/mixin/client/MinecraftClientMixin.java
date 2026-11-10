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
 * - Always loads on client (including Compatibility profile) to sustain telemetry.
 * - Measures render() duration in O(1) with zero Render Thread sorting/allocations.
 * - Syncs FPS Cap, VSync state, and local server presence to AdaptivePerformanceEngine.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Shadow
    @Final
    public GameOptions options;

    @Shadow
    public abstract boolean isIntegratedServerRunning();

    @Unique
    private long s1ind$frameStartNano = 0;

    @Unique
    private int s1ind$settingsSyncCounter = 0;

    @Inject(method = "run", at = @At("HEAD"))
    private void s1ind$onClientRun(CallbackInfo ci) {
        AdaptivePerformanceEngine.getInstance().startBackgroundWorker();
        if (S1indOptMod.config() != null && S1indOptMod.config().autoMemoryClean()) {
            MemoryCleaner.start();
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

        // Sync client render cap/vsync periodically (approx once every 128 frames)
        if ((++s1ind$settingsSyncCounter & 127) == 0 && this.options != null) {
            Integer cap = this.options.getMaxFps().getValue();
            Boolean vsync = this.options.getEnableVsync().getValue();
            AdaptivePerformanceEngine.getInstance().updateClientRenderSettings(
                cap != null ? cap : 260,
                vsync != null && vsync,
                this.isIntegratedServerRunning()
            );
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void s1ind$onClientStop(CallbackInfo ci) {
        AdaptivePerformanceEngine.getInstance().stopBackgroundWorker();
        MemoryCleaner.stop();
    }
}
