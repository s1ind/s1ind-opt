package com.s1ind.opt.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanNotificationInfo;
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
        boolean failOnRegister;
        final List<NotificationListener> listeners = new ArrayList<>();

        FakeGcBean(String name, boolean failOnRegister) {
            this.name = name;
            this.failOnRegister = failOnRegister;
        }

        @Override public void addNotificationListener(NotificationListener l, NotificationFilter f, Object h) {
            if (failOnRegister) throw new IllegalStateException("Mock transient JMX error");
            listeners.add(l);
        }
        @Override public void removeNotificationListener(NotificationListener l) { listeners.remove(l); }
        @Override public void removeNotificationListener(NotificationListener l, NotificationFilter f, Object h) { listeners.remove(l); }
        @Override public MBeanNotificationInfo[] getNotificationInfo() { return new MBeanNotificationInfo[0]; }
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
    void testPartialRegistrationRetriesOnlyFailedBeansWithoutDuplicatingHealthyOnes() {
        FakeGcBean flakyBean = new FakeGcBean("FlakyOldGen", true);
        FakeGcBean healthyBean = new FakeGcBean("G1 Young", false);

        // Pass 1: flakyBean fails, healthyBean succeeds
        GcMonitor.registerBeans(List.of(flakyBean, healthyBean));
        assertTrue(GcMonitor.isRegistered());
        assertFalse(GcMonitor.isFullyRegistered(), "Must report partial state when a bean failed");
        assertEquals(1, GcMonitor.getAttachedListenerCount());
        assertEquals(1, healthyBean.listeners.size());
        assertEquals(0, flakyBean.listeners.size());

        // Pass 2: flakyBean recovers! Re-calling registerBeans MUST attach flakyBean without duplicating healthyBean!
        flakyBean.failOnRegister = false;
        GcMonitor.registerBeans(List.of(flakyBean, healthyBean));
        assertTrue(GcMonitor.isFullyRegistered());
        assertEquals(2, GcMonitor.getAttachedListenerCount());
        assertEquals(1, healthyBean.listeners.size(), "Already-registered bean MUST NOT get a duplicate listener");
        assertEquals(1, flakyBean.listeners.size(), "Previously-failed bean MUST be retried and attached");

        // Unregister removes both cleanly
        GcMonitor.unregister();
        assertFalse(GcMonitor.isRegistered());
        assertFalse(GcMonitor.isFullyRegistered());
        assertEquals(0, healthyBean.listeners.size());
        assertEquals(0, flakyBean.listeners.size());
    }

    @Test
    void testMonotonicWindowShorterCycleAndExpiry() {
        AtomicLong clock = new AtomicLong(100_000_000_000L);
        GcMonitor.setClockForTesting(clock::get);

        // Long 120ms GC
        GcMonitor.recordEvent(120L, "G1 Young", "Allocation Failure");
        assertEquals(120L, GcMonitor.getLastCycleDurationMs());
        assertEquals(120L, GcMonitor.getMaxDuration60s());
        assertEquals(120L, GcMonitor.getTotalDuration60s());
        assertEquals(1, GcMonitor.getGcCyclesPerMinute());
        assertTrue(GcMonitor.isUnderGcPressure());

        // +20s: Shorter 35ms GC must update last event timestamp & stats
        clock.addAndGet(20_000_000_000L);
        long tShort = clock.get();
        GcMonitor.recordEvent(35L, "G1 Young", "Allocation Failure");
        assertEquals(35L, GcMonitor.getLastCycleDurationMs());
        assertEquals(tShort, GcMonitor.getLastEventTimestampNano());
        assertEquals(120L, GcMonitor.getMaxDuration60s());
        assertEquals(155L, GcMonitor.getTotalDuration60s());
        assertEquals(2, GcMonitor.getGcCyclesPerMinute());

        // +65s from start: 120ms event expires out of 60s window
        clock.set(165_000_000_000L);
        GcMonitor.refreshWindow();
        assertEquals(35L, GcMonitor.getMaxDuration60s());
        assertEquals(35L, GcMonitor.getTotalDuration60s());
        assertEquals(1, GcMonitor.getGcCyclesPerMinute());
        assertFalse(GcMonitor.isUnderGcPressure());
        assertEquals("N/A", GcMonitor.getStwPauseDisplay());
    }
}
