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
 * Worker thread tuning and priority balancing strictly for Vanilla Minecraft pools.
 * Does not touch executors or threads created by third-party mods.
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
        if (thread != null && (S1indOptMod.config() == null || S1indOptMod.config().lowerBackgroundThreadPriority())) {
            try {
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            } catch (SecurityException e) {
                S1indOptMod.LOG.warn("[S1ind] Unable to set IO thread priority for '{}'", name, e);
            }
        }
    }

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
        if (thread != null && (S1indOptMod.config() == null || S1indOptMod.config().lowerBackgroundThreadPriority())) {
            try {
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            } catch (SecurityException e) {
                S1indOptMod.LOG.warn("[S1ind] Unable to set ForkJoin thread priority for '{}'", name, e);
            }
        }
    }
}
