package com.s1ind.opt.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobAiPolicyAndSpatialTest {

    @AfterEach
    void tearDown() {
        PlayerSpatialIndex.clearCurrentThread();
    }

    @Test
    void testDistanceBoundaries15Exact16And17And32() {
        double threshold = 32.0;

        // 15 blocks -> MUST NOT skip regardless of stress or skipCounter
        for (int c = 1; c <= 12; c++) {
            assertFalse(MobAiPolicy.shouldSkipTick(15.0 * 15.0, threshold, StressLevel.NORMAL, c));
            assertFalse(MobAiPolicy.shouldSkipTick(15.0 * 15.0, threshold, StressLevel.CRITICAL, c));
        }

        // Exact boundary 16.0 blocks -> MUST NOT skip under any stress
        for (int c = 1; c <= 12; c++) {
            assertFalse(MobAiPolicy.shouldSkipTick(16.0 * 16.0, threshold, StressLevel.NORMAL, c),
                "Exactly 16.0 blocks must remain 100% vanilla");
            assertFalse(MobAiPolicy.shouldSkipTick(16.0 * 16.0, threshold, StressLevel.CRITICAL, c),
                "Exactly 16.0 blocks must never be throttled even under CRITICAL stress");
        }

        // 17 blocks -> NORMAL/MODERATE never skip; CRITICAL skips 1 in 2
        assertFalse(MobAiPolicy.shouldSkipTick(17.0 * 17.0, threshold, StressLevel.NORMAL, 1));
        assertFalse(MobAiPolicy.shouldSkipTick(17.0 * 17.0, threshold, StressLevel.MODERATE, 1));
        assertTrue(MobAiPolicy.shouldSkipTick(17.0 * 17.0, threshold, StressLevel.CRITICAL, 1));
        assertFalse(MobAiPolicy.shouldSkipTick(17.0 * 17.0, threshold, StressLevel.CRITICAL, 2));

        // 32 blocks (far zone >= threshold) -> NORMAL 1/2, CRITICAL 3/4
        assertTrue(MobAiPolicy.shouldSkipTick(32.0 * 32.0, threshold, StressLevel.NORMAL, 1));
        assertTrue(MobAiPolicy.shouldSkipTick(32.0 * 32.0, threshold, StressLevel.CRITICAL, 1));
        assertTrue(MobAiPolicy.shouldSkipTick(32.0 * 32.0, threshold, StressLevel.CRITICAL, 2));
        assertTrue(MobAiPolicy.shouldSkipTick(32.0 * 32.0, threshold, StressLevel.CRITICAL, 3));
        assertFalse(MobAiPolicy.shouldSkipTick(32.0 * 32.0, threshold, StressLevel.CRITICAL, 4));
    }

    @Test
    void testSpatialIndexMultiplePlayersAndEnterExit16BlockZone() {
        PlayerSpatialIndex spatial = PlayerSpatialIndex.forCurrentThread();
        Object fakeWorld = new Object();

        // Tick 1: 10 players far away (~100+ blocks), 1 mob at origin (0, 64, 0) -> dist >= 32 -> skipped
        spatial.beginFrame(fakeWorld, 1L);
        for (int i = 0; i < 10; i++) {
            spatial.addPlayer(80.0 + i * 5.0, 64.0, 80.0);
        }
        double distSqFar = spatial.findNearestDistanceSq(0, 64, 0, 32.0);
        assertEquals(Double.MAX_VALUE, distSqFar);

        // Tick 2: One player sprints into range (10, 64, 0) -> spatial lookup MUST immediately return <= 16b!
        spatial.beginFrame(fakeWorld, 2L);
        for (int i = 0; i < 9; i++) {
            spatial.addPlayer(80.0 + i * 5.0, 64.0, 80.0);
        }
        spatial.addPlayer(10.0, 64.0, 0.0);

        double distSqEntered = spatial.findNearestDistanceSq(0, 64, 0, 32.0);
        assertEquals(100.0, distSqEntered, 0.001);
        assertFalse(MobAiPolicy.shouldSkipTick(distSqEntered, 32.0, StressLevel.CRITICAL, 1),
            "Entering 16b zone must immediately disable skipping on the exact same tick");

        // Tick 3: Player exits to (35, 64, 0) -> throttling resumes on the same tick
        spatial.beginFrame(fakeWorld, 3L);
        spatial.addPlayer(35.0, 64.0, 0.0);
        double distSqExited = spatial.findNearestDistanceSq(0, 64, 0, 64.0);
        assertEquals(1225.0, distSqExited, 0.001);
        assertTrue(MobAiPolicy.shouldSkipTick(distSqExited, 32.0, StressLevel.NORMAL, 1));
    }

    @Test
    void testEntityCategoryAndDynamicGuards() {
        // Hostile / non-passive excluded
        assertFalse(MobAiPolicy.isEligibleCategory(false, false, false, true, true, true));

        // Villagers / Merchants protected
        assertFalse(MobAiPolicy.isEligibleCategory(true, true, false, true, true, true));

        // Golems protected
        assertFalse(MobAiPolicy.isEligibleCategory(true, false, true, true, true, true));

        // Modded third-party entities protected
        assertFalse(MobAiPolicy.isEligibleCategory(true, false, false, false, true, true));

        // Vanilla passive animal eligible
        assertTrue(MobAiPolicy.isEligibleCategory(true, false, false, true, true, true));

        // Dynamic gameplay guards: custom name, leashed, passengers, vehicle, tamed, breeding, combat
        assertTrue(MobAiPolicy.hasDynamicProtection(true, false, false, false, false, false, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, true, false, false, false, false, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, true, false, false, false, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, false, true, false, false, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, false, false, true, false, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, false, false, false, true, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, false, false, false, false, true));
        assertFalse(MobAiPolicy.hasDynamicProtection(false, false, false, false, false, false, false));
    }
}
