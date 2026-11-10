package com.s1ind.opt.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import java.lang.management.GarbageCollectorMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class GcMonitorTest {

    private static class FakeGcBean implements GarbageCollectorMXBean, javax.management.NotificationEmitter {
        private final String name;
        private final boolean failOnRegister;
        final List<NotificationListener> listeners = new ArrayList<>();

        FakeGcBean(String name, boolean failOnRegister) {
            this.name = name;
            this.failOnRegister = failOnRegister;
        }

        @Override public void addNotificationListener(NotificationListener l, NotificationFilter f, Object h) {
            if (failOnRegister) throw new IllegalStateException("Mock JMX failure");
            listeners.add(l);
        }
        @Override public void removeNotificationListener(NotificationListener l) { listeners.remove(l); }
        @Override public void removeNotificationListener(NotificationListener l, NotificationFilter f, Object h) { listeners.remove(l); }
        @Override public javax.management.MBeanNotificationInfo[] getNotificationInfo() { return new javax.management.MBeanNotificationInfo[0]; }
        @Override public long getCollectionCount() { return 0; }
        @Override public long getCollectionTime() { return 0; }
        @Override public String getName() { return name; }
        @Override public boolean isValid() { return true; }
        @Override public String[] getMemoryPoolNames() { return new String[0]; }
        @Override public ObjectName getObjectName() { return null; }
    }

    @BeforeEach
    void setUp() {
        GcMonitor.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        GcMonitor.resetForTesting();
    }

    @Test
    void testMultiBeanAndIdempotentAndPartialFailureRegistrationAndUnregister() {
        FakeGcBean failingBean = new FakeGcBean("FailingGC", true);
        FakeGcBean goodBean1 = new FakeGcBean("G1 Young Generation", false);
        FakeGcBean goodBean2 = new FakeGcBean("G1 Old Generation", false);

        GcMonitor.registerBeans(List.of(failingBean, goodBean1, goodBean2));

        assertTrue(GcMonitor.isRegistered());
        assertEquals(2, GcMonitor.getAttachedListenerCount(), "Must attach healthy beans even if one fails");
        assertEquals(1, goodBean1.listeners.size());
        assertEquals(1, goodBean2.listeners.size());

        // Repeated registration MUST be idempotent (no duplicate listeners)
        GcMonitor.registerBeans(List.of(goodBean1, goodBean2));
        assertEquals(2, GcMonitor.getAttachedListenerCount());
        assertEquals(1, goodBean1.listeners.size());

        // Unregister removes all listeners cleanly
        GcMonitor.unregister();
        assertFalse(GcMonitor.isRegistered());
        assertEquals(0, GcMonitor.getAttachedListenerCount());
        assertEquals(0, goodBean1.listeners.size());
        assertEquals(0, goodBean2.listeners.size());
    }

    @Test
    void testMonotonicWindowShorterCycleAndExpiry() {
        AtomicLong clock = new AtomicLong(100_000_000_000L); // t = 100s
        GcMonitor.setClockForTesting(clock::get);

        // Long 120ms GC at t=100s
        GcMonitor.recordEvent(120L, "G1 Young", "Allocation Failure");
        assertEquals(120L, GcMonitor.getLastCycleDurationMs());
        assertEquals(120L, GcMonitor.getMaxDuration60s());
        assertEquals(120L, GcMonitor.getTotalDuration60s());
        assertEquals(1, GcMonitor.getGcCyclesPerMinute());
        assertTrue(GcMonitor.isUnderGcPressure());

        // Advance +20s -> Shorter 35ms GC must still update last timestamp and rolling totals!
        clock.addAndGet(20_000_000_000L);
        long tShort = clock.get();
        GcMonitor.recordEvent(35L, "G1 Young", "Allocation Failure");
        assertEquals(35L, GcMonitor.getLastCycleDurationMs());
        assertEquals(tShort, GcMonitor.getLastEventTimestampNano());
        assertEquals(120L, GcMonitor.getMaxDuration60s());
        assertEquals(155L, GcMonitor.getTotalDuration60s());
        assertEquals(2, GcMonitor.getGcCyclesPerMinute());

        // Advance past 60s window for the 120ms event (t = 165s) -> only 35ms remains in 60s window!
        clock.set(165_000_000_000L);
        GcMonitor.refreshWindow();
        assertEquals(35L, GcMonitor.getMaxDuration60s());
        assertEquals(35L, GcMonitor.getTotalDuration60s());
        assertEquals(1, GcMonitor.getGcCyclesPerMinute());
        assertFalse(GcMonitor.isUnderGcPressure());
        assertEquals("N/A", GcMonitor.getStwPauseDisplay());
    }
}
