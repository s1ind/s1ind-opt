package com.s1ind.opt.mixin.common;

import com.s1ind.opt.S1indOptMod;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker thread tuning and thread priority balancing.
 *
 * 1. Overrides Util.getAvailableBackgroundThreads() to allocate a balanced core count.
 * 2. Assigns Thread.NORM_PRIORITY - 1 (4) to IO and background worker threads
 *    to prevent them from preempting the Render Thread (Priority 10).
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

    /**
     * Minecraft IO / Download thread creation factory.
     */
    @Inject(
        method = "method_27956",
        at = @At("RETURN")
    )
    private static void s1ind$setIoThreadPriority(
        String name,
        AtomicInteger counter,
        boolean daemon,
        Runnable runnable,
        CallbackInfoReturnable<Thread> cir
    ) {
        Thread thread = cir.getReturnValue();
        if (thread != null) {
            thread.setPriority(Thread.NORM_PRIORITY - 1); // 4
        }
    }

    /**
     * Minecraft ForkJoinWorkerThread creation factory.
     */
    @Inject(
        method = "method_28123",
        at = @At("RETURN")
    )
    private static void s1ind$setForkJoinThreadPriority(
        String name,
        AtomicInteger counter,
        ForkJoinPool pool,
        CallbackInfoReturnable<ForkJoinWorkerThread> cir
    ) {
        ForkJoinWorkerThread thread = cir.getReturnValue();
        if (thread != null) {
            thread.setPriority(Thread.NORM_PRIORITY - 1); // 4
        }
    }
}
