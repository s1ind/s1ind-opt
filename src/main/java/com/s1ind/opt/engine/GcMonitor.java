package com.s1ind.opt.engine;

import com.s1ind.opt.S1indOptMod;
import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.MBeanNotificationInfo;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Event-driven Garbage Collector telemetry via JMX NotificationEmitter.
 *
 * - Per-MXBean independent registration state: retries failed MXBeans on subsequent
 *   calls without ever duplicating listeners on already-registered MXBeans.
 * - Cleanly unregisters all attached listeners upon shutdown.
 * - Maintains a 60-second monotonic history (System.nanoTime()) capturing every GC cycle.
 * - Strictly separates GC Cycle Duration (JMX GcInfo.getDuration()) from STW Pause (N/A).
 */
public final class GcMonitor {

    private record GcEvent(long timestampNano, long durationMs, String gcName, String cause) {}
    private record AttachedListener(NotificationEmitter emitter, NotificationListener listener) {}

    public static final long WINDOW_NANOS = 60_000_000_000L;
    private static final long RECENT_PRESSURE_NANOS = 15_000_000_000L;

    private static final Deque<GcEvent> history = new ArrayDeque<>();
    private static final Map<GarbageCollectorMXBean, AttachedListener> attachedByBean = new IdentityHashMap<>();

    private static LongSupplier nanoClock = System::nanoTime;

    private static volatile long lastCycleDurationMs = 0;
    private static volatile long lastEventTimestampNano = 0;
    private static volatile String lastGcCause = "none";
    private static volatile long maxDuration60s = 0;
    private static volatile long totalDuration60s = 0;
    private static volatile int gcCycles60s = 0;
    private static volatile long totalEventsLifetime = 0;
    private static volatile long lastHeavyEventNano = 0;
    private static volatile boolean fullyRegistered = false;

    private GcMonitor() {}

    public static synchronized void register() {
        registerBeans(ManagementFactory.getGarbageCollectorMXBeans());
    }

    /**
     * Registers JMX listeners per GC MXBean. Retries previously failed MXBeans
     * without duplicating listeners on beans already registered.
     */
    public static synchronized void registerBeans(List<GarbageCollectorMXBean> beans) {
        if (beans == null || beans.isEmpty()) {
            return;
        }

        boolean allAttached = true;
        for (GarbageCollectorMXBean bean : beans) {
            if (bean == null || attachedByBean.containsKey(bean)) {
                continue;
            }
            if (!(bean instanceof NotificationEmitter emitter)) {
                // Collector does not emit JMX notifications (safe skip)
                continue;
            }
            MBeanNotificationInfo[] info = emitter.getNotificationInfo();
            if (info == null) {
                continue;
            }

            NotificationListener listener = (notification, handback) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                    return;
                }
                try {
                    CompositeData cd = (CompositeData) notification.getUserData();
                    GarbageCollectionNotificationInfo gcInfo = GarbageCollectionNotificationInfo.from(cd);
                    long durationMs = gcInfo.getGcInfo().getDuration();
                    String cause = gcInfo.getGcCause() != null ? gcInfo.getGcCause() : "unknown";
                    String name = gcInfo.getGcName() != null ? gcInfo.getGcName() : "unknown";

                    recordEvent(durationMs, name, cause);
                } catch (Exception e) {
                    S1indOptMod.LOG.warn("[S1ind] Failed parsing JMX GC notification for '{}'", bean.getName(), e);
                }
            };

            try {
                emitter.addNotificationListener(listener, null, null);
                attachedByBean.put(bean, new AttachedListener(emitter, listener));
            } catch (Exception e) {
                allAttached = false;
                S1indOptMod.LOG.warn("[S1ind] Partial JMX registration failure for MXBean '{}'; will retry later", bean.getName(), e);
            }
        }

        fullyRegistered = allAttached && !attachedByBean.isEmpty();
    }

    public static synchronized void unregister() {
        for (AttachedListener attached : attachedByBean.values()) {
            try {
                attached.emitter().removeNotificationListener(attached.listener());
            } catch (Exception e) {
                S1indOptMod.LOG.warn("[S1ind] Error detaching JMX GC listener during shutdown", e);
            }
        }
        attachedByBean.clear();
        fullyRegistered = false;
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
    public static boolean isFullyRegistered()      { return fullyRegistered; }
    public static boolean isRegistered()           { return !attachedByBean.isEmpty(); }
    public static int getAttachedListenerCount()   { return attachedByBean.size(); }

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
