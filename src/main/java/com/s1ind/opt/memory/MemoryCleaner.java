package com.s1ind.opt.memory;

import com.s1ind.opt.S1indOptMod;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Intelligent memory pressure watchdog.
 *
 * Runs on a daemon thread. Instead of blindly forcing System.gc() in a tight loop,
 * it monitors heap utilization against a configured threshold (default: 85%).
 * Only when memory pressure threatens to trigger a full Stop-The-World GC freeze
 * does it request proactive background reclamation.
 */
public class MemoryCleaner {

    private static ScheduledExecutorService scheduler;
    private static volatile boolean running = false;

    public static synchronized void start(int intervalSeconds, int thresholdPercent) {
        if (running && scheduler != null && !scheduler.isShutdown()) {
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "S1ind-Memory-Watchdog");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        scheduler.scheduleAtFixedRate(
            () -> checkAndClean(thresholdPercent),
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );

        running = true;
        S1indOptMod.LOG.info("[S1ind Memory] Watchdog active: checking every {}s (triggers above {}% heap)",
            intervalSeconds, thresholdPercent);
    }

    public static void checkAndClean(int thresholdPercent) {
        try {
            Runtime rt = Runtime.getRuntime();
            long maxBytes = rt.maxMemory();
            long totalBytes = rt.totalMemory();
            long freeBytes = rt.freeMemory();
            long usedBytes = totalBytes - freeBytes;

            double usedRatio = (double) usedBytes / maxBytes;
            int usedPercent = (int) (usedRatio * 100);

            if (usedPercent >= thresholdPercent) {
                long beforeMb = usedBytes / (1024 * 1024);
                S1indOptMod.LOG.warn("[S1ind Memory] High heap pressure ({}% / {} MB). Triggering proactive GC...",
                    usedPercent, beforeMb);

                System.gc();

                long afterBytes = rt.totalMemory() - rt.freeMemory();
                long afterMb = afterBytes / (1024 * 1024);
                long freedMb = beforeMb - afterMb;

                S1indOptMod.LOG.info("[S1ind Memory] Reclamation complete: {} MB -> {} MB (Freed {} MB)",
                    beforeMb, afterMb, freedMb);
            }
        } catch (Throwable t) {
            S1indOptMod.LOG.warn("[S1ind Memory] Watchdog check failed: {}", t.getMessage());
        }
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        running = false;
        S1indOptMod.LOG.info("[S1ind Memory] Watchdog stopped");
    }
}
