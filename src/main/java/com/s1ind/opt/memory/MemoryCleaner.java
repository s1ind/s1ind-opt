package com.s1ind.opt.memory;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.config.S1indConfig;
import com.s1ind.opt.engine.AdaptiveTaskScheduler;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskPriority;

import java.util.Locale;

/**
 * Low-overhead memory pressure watchdog.
 *
 * Disabled by default (recommended only for <=3GB heap environments).
 * Schedules checks via AdaptiveTaskScheduler so routine inspections defer during frame spikes.
 * Note: System.gc() is an advisory request to the JVM; actual reclamation and pause time
 * are governed by the JVM Garbage Collector implementation (G1GC / ZGC).
 */
public final class MemoryCleaner {

    private static volatile boolean running = false;
    private static Thread watchdogThread = null;

    private MemoryCleaner() {}

    public static synchronized void start() {
        S1indConfig config = S1indOptMod.config();
        if (config == null || !config.autoMemoryClean()) {
            S1indOptMod.LOG.info("[S1ind] Memory Watchdog is disabled (default for normal RAM environments).");
            return;
        }

        if (running) {
            return;
        }
        running = true;

        watchdogThread = new Thread(MemoryCleaner::runLoop, "S1ind-Memory-Watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.setPriority(Thread.MIN_PRIORITY);
        watchdogThread.start();

        S1indOptMod.LOG.info("[S1ind] Memory Watchdog active (interval: {}s, threshold: {}%).",
            config.memoryCheckIntervalSeconds(),
            config.memoryPressureThresholdPercent()
        );
    }

    public static synchronized void stop() {
        running = false;
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }
    }

    private static void runLoop() {
        while (running) {
            try {
                int intervalSec = S1indOptMod.config() != null
                    ? S1indOptMod.config().memoryCheckIntervalSeconds()
                    : 30;
                Thread.sleep(intervalSec * 1000L);

                AdaptiveTaskScheduler.getInstance().submit(MemoryCleaner::checkAndClean, TaskPriority.LOW);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                S1indOptMod.LOG.warn("[S1ind] Memory Watchdog encountered an error", e);
            }
        }
    }

    private static void checkAndClean() {
        S1indConfig config = S1indOptMod.config();
        if (config == null || !config.autoMemoryClean()) {
            return;
        }

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = allocatedMemory - freeMemory;

        if (maxMemory <= 0) return;

        double usedPercent = ((double) usedMemory / (double) maxMemory) * 100.0;
        long freeRemainingBytes = maxMemory - usedMemory;
        long criticalThresholdBytes = 250L * 1024L * 1024L;

        if (usedPercent >= config.memoryPressureThresholdPercent() && freeRemainingBytes < criticalThresholdBytes) {
            long usedBeforeMb = usedMemory / (1024 * 1024);
            System.gc();

            long usedAfterMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long reclaimedMb = Math.max(0, usedBeforeMb - usedAfterMb);

            String formattedPct = String.format(Locale.ROOT, "%.1f", usedPercent);
            S1indOptMod.LOG.info("[S1ind] Advisory GC requested at {}% heap: delta ~{} MB (used: {} MB -> {} MB)",
                formattedPct, reclaimedMb, usedBeforeMb, usedAfterMb);
        }
    }
}
