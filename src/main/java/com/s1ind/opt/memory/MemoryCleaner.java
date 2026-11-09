package com.s1ind.opt.memory;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.config.S1indConfig;

/**
 * Low-overhead memory pressure watchdog.
 *
 * Runs as a daemon thread at MIN_PRIORITY.
 * By default disabled in v2.5.0, intended as a safeguard for low-RAM machines (<=3GB).
 *
 * Does not spam System.gc(). Only triggers a collection when heap usage exceeds
 * the configured threshold (default 88%) and free memory is critical (<250MB).
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
        watchdogThread.setPriority(Thread.MIN_PRIORITY); // Priority 1: never interrupts game logic
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

                checkAndClean();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                S1indOptMod.LOG.warn("[S1ind] Memory Watchdog encountered an error", t);
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
        long criticalThresholdBytes = 250L * 1024L * 1024L; // 250 MB

        // Only invoke GC if heap is critically filled and remaining capacity is scarce
        if (usedPercent >= config.memoryPressureThresholdPercent() && freeRemainingBytes < criticalThresholdBytes) {
            long usedBeforeMb = usedMemory / (1024 * 1024);
            System.gc();

            long usedAfterMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long reclaimedMb = Math.max(0, usedBeforeMb - usedAfterMb);

            S1indOptMod.LOG.info("[S1ind] Emergency GC triggered under high pressure ({:.1f}%): reclaimed ~{} MB (used: {} MB -> {} MB)",
                usedPercent, reclaimedMb, usedBeforeMb, usedAfterMb);
        }
    }
}
