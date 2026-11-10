package com.s1ind.opt.memory;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.config.S1indConfig;
import com.s1ind.opt.engine.AdaptiveTaskScheduler;
import com.s1ind.opt.engine.AdaptiveTaskScheduler.TaskPriority;

import java.util.Locale;

/**
 * Non-intrusive Heap Pressure & Allocation Rate Diagnostics Watchdog.
 *
 * Disabled by default unless explicitly enabled in config.
 * Never calls System.gc() automatically or claims VRAM/texture management:
 * monitors JVM heap saturation and logs advisory memory warnings when critical.
 */
public final class MemoryCleaner {

    private static volatile boolean running = false;
    private static volatile double lastAllocationRateMbPerSec = 0.0;
    private static volatile double lastHeapUsagePercent = 0.0;
    private static Thread watchdogThread = null;
    private static long lastSampleUsedBytes = -1L;
    private static long lastSampleNano = 0L;

    private MemoryCleaner() {}

    public static synchronized void start() {
        S1indConfig config = S1indOptMod.config();
        if (config == null || !config.autoMemoryClean()) {
            S1indOptMod.LOG.info("[S1ind] Diagnostic Memory Monitor is disabled (default).");
            return;
        }

        if (running) {
            return;
        }
        running = true;

        Runtime rt = Runtime.getRuntime();
        lastSampleUsedBytes = rt.totalMemory() - rt.freeMemory();
        lastSampleNano = System.nanoTime();

        watchdogThread = new Thread(MemoryCleaner::runLoop, "S1ind-Memory-Diagnostics");
        watchdogThread.setDaemon(true);
        watchdogThread.setPriority(Thread.MIN_PRIORITY);
        watchdogThread.start();

        S1indOptMod.LOG.info("[S1ind] Diagnostic Memory Monitor active (interval={}s, warnThreshold={}%).",
            config.memoryCheckIntervalSeconds(),
            config.memoryPressureThresholdPercent()
        );
    }

    public static synchronized void stop() {
        running = false;
        Thread t = watchdogThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!t.isAlive()) {
                watchdogThread = null;
            }
        }
    }

    private static void runLoop() {
        while (running) {
            try {
                int intervalSec = S1indOptMod.config() != null
                    ? S1indOptMod.config().memoryCheckIntervalSeconds()
                    : 30;
                Thread.sleep(intervalSec * 1000L);

                if (running) {
                    AdaptiveTaskScheduler.getInstance().submitCoalescing(
                        MemoryCleaner::sampleHeapDiagnostics,
                        TaskPriority.LOW,
                        "memory-diagnostics"
                    );
                }
            } catch (InterruptedException e) {
                if (!running) break;
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                S1indOptMod.LOG.warn("[S1ind] Memory Diagnostic Watchdog loop encountered an error", e);
            }
        }
    }

    private static void sampleHeapDiagnostics() {
        S1indConfig config = S1indOptMod.config();
        if (config == null || !config.autoMemoryClean()) {
            return;
        }

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        if (maxMemory <= 0) return;

        long nowNano = System.nanoTime();
        if (lastSampleUsedBytes >= 0 && nowNano > lastSampleNano) {
            long deltaBytes = usedMemory - lastSampleUsedBytes;
            double dtSec = (nowNano - lastSampleNano) / 1_000_000_000.0;
            if (deltaBytes >= 0 && dtSec > 0.0) {
                lastAllocationRateMbPerSec = (deltaBytes / (1024.0 * 1024.0)) / dtSec;
            }
        }
        lastSampleUsedBytes = usedMemory;
        lastSampleNano = nowNano;

        double usedPercent = ((double) usedMemory / (double) maxMemory) * 100.0;
        lastHeapUsagePercent = usedPercent;

        long remainingHeadroomBytes = maxMemory - usedMemory;
        long criticalHeadroomBytes = 250L * 1024L * 1024L;

        if (usedPercent >= config.memoryPressureThresholdPercent() && remainingHeadroomBytes < criticalHeadroomBytes) {
            String formattedPct = String.format(Locale.ROOT, "%.1f", usedPercent);
            String formattedRate = String.format(Locale.ROOT, "%.1f", lastAllocationRateMbPerSec);
            S1indOptMod.LOG.warn(
                "[S1ind] High Java Heap pressure detected: {}% used ({} MB free headroom remaining, net delta ~{} MB/s). Consider checking mod memory usage or increasing -Xmx.",
                formattedPct, remainingHeadroomBytes / (1024 * 1024), formattedRate
            );
        }
    }

    public static double getLastAllocationRateMbPerSec() { return lastAllocationRateMbPerSec; }
    public static double getLastHeapUsagePercent()       { return lastHeapUsagePercent; }
    public static boolean isRunning()                    { return running; }
}
