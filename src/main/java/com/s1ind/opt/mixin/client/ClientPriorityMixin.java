package com.s1ind.opt.mixin.client;

import com.s1ind.opt.S1indOptMod;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Isolated mixin elevating Render Thread priority when allowed by profile and JVM.
 * Can be disabled via S1indMixinPlugin without affecting telemetry or lifecycle hooks.
 */
@Mixin(MinecraftClient.class)
public abstract class ClientPriorityMixin {

    @Inject(method = "run", at = @At("HEAD"))
    private void s1ind$elevateRenderThreadPriority(CallbackInfo ci) {
        if (S1indOptMod.config() != null && S1indOptMod.config().boostRenderThreadPriority()) {
            try {
                Thread current = Thread.currentThread();
                int targetPriority = Thread.MAX_PRIORITY;
                if (current.getThreadGroup() != null) {
                    targetPriority = Math.min(targetPriority, current.getThreadGroup().getMaxPriority());
                }
                current.setPriority(targetPriority);
                S1indOptMod.LOG.info("[S1ind] Render Thread priority set to {} (group ceiling respected)", targetPriority);
            } catch (SecurityException e) {
                S1indOptMod.LOG.warn("[S1ind] JVM denied Render Thread priority elevation", e);
            }
        }
    }
}
