package com.s1ind.opt.mixin.common;

import com.s1ind.opt.engine.AdaptivePerformanceEngine;
import com.s1ind.opt.engine.PlayerSpatialIndex;
import com.s1ind.opt.memory.MemoryCleaner;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Measures integrated/dedicated server tick duration (MSPT) and manages
 * headless lifecycle cleanup upon server shutdown.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Shadow
    public abstract boolean isDedicated();

    @Unique
    private long s1ind$tickStartNano = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void s1ind$beforeServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        s1ind$tickStartNano = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void s1ind$afterServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (s1ind$tickStartNano > 0) {
            long duration = System.nanoTime() - s1ind$tickStartNano;
            AdaptivePerformanceEngine.getInstance().recordTick(duration);
        }
    }

    @Inject(method = "shutdown", at = @At("RETURN"))
    private void s1ind$onServerShutdown(CallbackInfo ci) {
        PlayerSpatialIndex.clearCurrentThread();

        if (this.isDedicated()) {
            AdaptivePerformanceEngine.getInstance().stopBackgroundWorker();
            MemoryCleaner.stop();
        } else {
            AdaptivePerformanceEngine.getInstance().onLocalServerStopped();
        }
    }
}
