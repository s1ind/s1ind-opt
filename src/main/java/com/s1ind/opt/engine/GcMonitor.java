package com.s1ind.opt.engine;

import com.s1ind.opt.S1indOptMod;
import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Event-driven Garbage Collector telemetry via JMX NotificationEmitter.
 *
 * Maintains a rolling 60-second monotonic time window (System.nanoTime) of completed GC cycles.
 * Distinguishes total GC cycle frequency, rolling sum, and max single cycle duration
 * (note: for concurrent collectors like G1/ZGC, getDuration() covers phase duration and
 * may include concurrent work rather than pure application STW pause alone).
 */
public final class GcMonitor {

    private record GcEvent(long timestampNano, long durationMs, String gcName, String cause) {}

    private static final long WINDOW_NANOS = 60_000_000_000L; // 60 seconds
    private static final long RECENT_STALL_NANOS = 15_000_000_000L; // 15 seconds
    private static final Deque<GcEvent> history = new ArrayDeque<>();

    private static volatile long lastDurationMs = 0;
    private static volatile String lastGcCause = "none";
    private static volatile long maxDuration60s = 0;
    private static volatile long totalDuration60s = 0;
    private static volatile int gcCount60s = 0;
    private static volatile long lastHeavyEventNano = 0;
    private static boolean registered = false;

    private GcMonitor() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        try {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            NotificationListener listener = (notification, handback) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                    return;
                }
                try {
                    CompositeData cd = (CompositeData) notification.getUserData();
                    GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(cd);
                    long durationMs = info.getGcInfo().getDuration();
                    String cause = info.getGcCause() != null ? info.getGcCause() : "unknown";
                    String name = info.getGcName() != null ? info.getGcName() : "unknown";

                    recordEvent(durationMs, name, cause);
                } catch (Exception e) {
                    S1indOptMod.LOG.warn("[S1ind] Failed to parse JMX GC notification", e);
                }
            };

            for (GarbageCollectorMXBean bean : gcBeans) {
                if (bean instanceof NotificationEmitter emitter) {
                    emitter.addNotificationListener(listener, null, null);
                }
            }
        } catch (Exception e) {
            S1indOptMod.LOG.warn("[S1ind] Unable to attach JMX GC listeners", e);
        }
    }

    private static synchronized void recordEvent(long durationMs, String gcName, String cause) {
        long nowNano = System.nanoTime();
        lastDurationMs = durationMs;
        lastGcCause = cause;
        history.addLast(new GcEvent(nowNano, durationMs, gcName, cause));

        if (durationMs >= 50L) {
            lastHeavyEventNano = nowNano;
        }
        pruneAndRecomputeLocked(nowNano);
    }

    private static void pruneAndRecomputeLocked(long nowNano) {
        while (!history.isEmpty() && (nowNano - history.peekFirst().timestampNano()) > WINDOW_NANOS) {
            history.removeFirst();
        }
        long max = 0;
        long sum = 0;
        for (GcEvent e : history) {
            if (e.durationMs() > max) {
                max = e.durationMs();
            }
            sum += e.durationMs();
        }
        maxDuration60s = max;
        totalDuration60s = sum;
        gcCount60s = history.size();
    }

    public static synchronized void refreshWindow() {
        pruneAndRecomputeLocked(System.nanoTime());
    }

    public static long getLastDurationMs()   { return lastDurationMs; }
    public static long getMaxDuration60s()   { return maxDuration60s; }
    public static long getTotalDuration60s() { return totalDuration60s; }
    public static int  getGcCount60s()       { return gcCount60s; }
    public static String getLastGcCause()    { return lastGcCause; }

    public static boolean isRecentlyGcStalled() {
        long nowNano = System.nanoTime();
        return maxDuration60s >= 50L && (nowNano - lastHeavyEventNano) < RECENT_STALL_NANOS;
    }

    public static synchronized void resetForTesting() {
        history.clear();
        lastDurationMs = 0;
        maxDuration60s = 0;
        totalDuration60s = 0;
        gcCount60s = 0;
        lastHeavyEventNano = 0;
    }
}
