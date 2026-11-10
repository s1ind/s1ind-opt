package com.s1ind.opt.engine;

import com.s1ind.opt.S1indOptMod;
import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Event-driven Garbage Collector telemetry via JMX NotificationEmitter.
 *
 * - Idempotent, per-MXBean resilient listener registration & clean unregistration.
 * - Maintains a 60-second monotonic history (System.nanoTime()) capturing every GC cycle.
 * - Strictly separates GC Cycle Duration (JMX GcInfo.getDuration()) from true STW Pause
 *   (which is honestly reported as N/A where collectors mix concurrent and stop-the-world work).
 */
public final class GcMonitor {

    private record GcEvent(long timestampNano, long durationMs, String gcName, String cause) {}
    private record AttachedListener(NotificationEmitter emitter, NotificationListener listener) {}

    public static final long WINDOW_NANOS = 60_000_000_000L; // 60 seconds
    private static final long RECENT_PRESSURE_NANOS = 15_000_000_000L; // 15 seconds

    private static final Deque<GcEvent> history = new ArrayDeque<>();
    private static final List<AttachedListener> attachedListeners = new ArrayList<>();

    private static LongSupplier nanoClock = System::nanoTime;

    private static volatile long lastCycleDurationMs = 0;
    private static volatile long lastEventTimestampNano = 0;
    private static volatile String lastGcCause = "none";
    private static volatile long maxDuration60s = 0;
    private static volatile long totalDuration60s = 0;
    private static volatile int gcCycles60s = 0;
    private static volatile long totalEventsLifetime = 0;
    private static volatile long lastHeavyEventNano = 0;
    private static volatile boolean registered = false;

    private GcMonitor() {}

    public static synchronized void register() {
        registerBeans(ManagementFactory.getGarbageCollectorMXBeans());
    }

    /**
     * Registers JMX listeners independently per GC MXBean.
     * Safe for repeated calls and resilient to partial bean failures.
     */
    public static synchronized void registerBeans(List<GarbageCollectorMXBean> beans) {
        if (registered || beans == null) {
            return;
        }

        int attachedCount = 0;
        for (GarbageCollectorMXBean bean : beans) {
            if (bean instanceof NotificationEmitter emitter) {
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
                        S1indOptMod.LOG.warn("[S1ind] Failed to process JMX GC notification", e);
                    }
                };

                try {
                    emitter.addNotificationListener(listener, null, null);
                    attachedListeners.add(new AttachedListener(emitter, listener));
                    attachedCount++;
                } catch (Exception e) {
                    S1indOptMod.LOG.warn("[S1ind] Failed to attach GC listener for MXBean '{}'", bean.getName(), e);
                }
            }
        }

        if (attachedCount > 0) {
            registered = true;
        }
    }

    public static synchronized void unregister() {
        for (AttachedListener attached : attachedListeners) {
            try {
                attached.emitter().removeNotificationListener(attached.listener());
            } catch (Exception e) {
                S1indOptMod.LOG.warn("[S1ind] Failed cleanly removing JMX GC listener", e);
            }
        }
        attachedListeners.clear();
        registered = false;
    }

    public static synchronized void recordEvent(long durationMs, String gcName, String cause) {
        long nowNano = nanoClock.getAsLong();
        lastCycleDurationMs = durationMs;
        lastEventTimestampNano = nowNano;
        lastGcCause = cause != null ? cause : "unknown";
        totalEventsLifetime++;

        history.addLast(new GcEvent(nowNano, durationMs, gcName, lastGcCause));

        if (durationMs >= 50L) {
            lastHeavyEventNano = nowNano;
        }
        pruneAndRecomputeLocked(nowNano);
    }

    public static synchronized void refreshWindow() {
        pruneAndRecomputeLocked(nanoClock.getAsLong());
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
        gcCycles60s = history.size();
    }

    public static long getLastCycleDurationMs()    { return lastCycleDurationMs; }
    public static long getLastEventTimestampNano() { return lastEventTimestampNano; }
    public static long getMaxDuration60s()         { return maxDuration60s; }
    public static long getTotalDuration60s()       { return totalDuration60s; }
    public static int  getGcCyclesPerMinute()      { return gcCycles60s; }
    public static long getTotalEventsLifetime()    { return totalEventsLifetime; }
    public static String getLastGcCause()          { return lastGcCause; }
    public static boolean isRegistered()           { return registered; }
    public static int getAttachedListenerCount()   { return attachedListeners.size(); }

    /**
     * JMX GcInfo.getDuration() measures total collector cycle duration rather than
     * strict mutator Stop-The-World pause time on modern concurrent collectors.
     */
    public static String getStwPauseDisplay() {
        return "N/A";
    }

    public static boolean isUnderGcPressure() {
        long nowNano = nanoClock.getAsLong();
        boolean recentHeavy = (maxDuration60s >= 50L) && (nowNano - lastHeavyEventNano < RECENT_PRESSURE_NANOS);
        boolean sustainedThrash = totalDuration60s >= 1_500L || gcCycles60s >= 20;
        return recentHeavy || sustainedThrash;
    }

    public static synchronized void setClockForTesting(LongSupplier clock) {
        nanoClock = clock != null ? clock : System::nanoTime;
    }

    public static synchronized void resetForTesting() {
        unregister();
        history.clear();
        nanoClock = System::nanoTime;
        lastCycleDurationMs = 0;
        lastEventTimestampNano = 0;
        lastGcCause = "none";
        maxDuration60s = 0;
        totalDuration60s = 0;
        gcCycles60s = 0;
        totalEventsLifetime = 0;
        lastHeavyEventNano = 0;
    }
}
