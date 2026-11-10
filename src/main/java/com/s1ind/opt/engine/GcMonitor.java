package com.s1ind.opt.engine;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Event-driven Garbage Collector monitor using JMX NotificationEmitter.
 *
 * Unlike polling GarbageCollectorMXBean.getCollectionTime(), this receives real
 * notification events on individual completed GC cycles with exact pause duration
 * and GC cause.
 */
public final class GcMonitor {

    private static volatile long lastPauseMs = 0;
    private static volatile long maxRecentPauseMs = 0;
    private static volatile long lastHeavyGcTimestampMs = 0;
    private static volatile long totalGcEvents = 0;
    private static volatile String lastGcCause = "none";
    private static boolean registered = false;

    private GcMonitor() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        NotificationListener listener = (notification, handback) -> {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                return;
            }
            try {
                CompositeData cd = (CompositeData) notification.getUserData();
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(cd);
                long durationMs = info.getGcInfo().getDuration();
                String cause = info.getGcCause();

                lastPauseMs = durationMs;
                lastGcCause = cause != null ? cause : "unknown";
                totalGcEvents++;

                long now = System.currentTimeMillis();
                if (durationMs > maxRecentPauseMs || (now - lastHeavyGcTimestampMs > 60_000L)) {
                    maxRecentPauseMs = durationMs;
                    lastHeavyGcTimestampMs = now;
                }
            } catch (Throwable ignored) {
                // Non-fatal in restricted JVM environments
            }
        };

        for (GarbageCollectorMXBean bean : gcBeans) {
            if (bean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(listener, null, null);
            }
        }
    }

    public static long getLastPauseMs() {
        return lastPauseMs;
    }

    public static long getMaxRecentPauseMs() {
        long now = System.currentTimeMillis();
        if (now - lastHeavyGcTimestampMs > 60_000L) {
            return lastPauseMs;
        }
        return maxRecentPauseMs;
    }

    public static long getTotalGcEvents() {
        return totalGcEvents;
    }

    public static String getLastGcCause() {
        return lastGcCause;
    }

    public static boolean isRecentlyGcStalled() {
        long now = System.currentTimeMillis();
        return getMaxRecentPauseMs() >= 50L && (now - lastHeavyGcTimestampMs < 15_000L);
    }
}
