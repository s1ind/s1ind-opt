package com.s1ind.opt.mixin.common;

import com.s1ind.opt.S1indOptMod;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Custom background worker thread budget.
 *
 * Configures the thread count returned by Util.getAvailableBackgroundThreads(),
 * which Minecraft uses to size its main worker ForkJoinPool.
 */
@Mixin(Util.class)
public abstract class UtilMixin {

    @Inject(
        method = "getAvailableBackgroundThreads",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void s1ind$overrideBackgroundThreads(CallbackInfoReturnable<Integer> cir) {
        if (S1indOptMod.config() != null) {
            int threads = S1indOptMod.config().backgroundThreadCount();
            if (threads > 0) {
                cir.setReturnValue(threads);
            }
        }
    }
}
