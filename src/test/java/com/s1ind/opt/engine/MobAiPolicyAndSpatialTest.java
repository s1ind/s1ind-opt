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
    void testExactDistanceBoundaries15999Exact16And16001And32() {
        double threshold = 32.0;

        // 15.999 blocks -> NEVER skip
        assertFalse(MobAiPolicy.shouldSkipTick(15.999 * 15.999, threshold, StressLevel.NORMAL, 1));
        assertFalse(MobAiPolicy.shouldSkipTick(15.999 * 15.999, threshold, StressLevel.CRITICAL, 1));

        // Exactly 16.0 blocks -> NEVER skip under ANY stress level
        for (int c = 1; c <= 12; c++) {
            assertFalse(MobAiPolicy.shouldSkipTick(16.0 * 16.0, threshold, StressLevel.NORMAL, c));
            assertFalse(MobAiPolicy.shouldSkipTick(16.0 * 16.0, threshold, StressLevel.MODERATE, c));
            assertFalse(MobAiPolicy.shouldSkipTick(16.0 * 16.0, threshold, StressLevel.CRITICAL, c));
        }

        // 16.001 blocks (just outside interaction boundary -> intermediate zone)
        assertFalse(MobAiPolicy.shouldSkipTick(16.001 * 16.001, threshold, StressLevel.NORMAL, 1));
        assertFalse(MobAiPolicy.shouldSkipTick(16.001 * 16.001, threshold, StressLevel.MODERATE, 1));
        assertTrue(MobAiPolicy.shouldSkipTick(16.001 * 16.001, threshold, StressLevel.CRITICAL, 1));

        // 32.0 blocks (far zone >= threshold)
        assertTrue(MobAiPolicy.shouldSkipTick(32.0 * 32.0, threshold, StressLevel.NORMAL, 1));
        assertTrue(MobAiPolicy.shouldSkipTick(32.0 * 32.0, threshold, StressLevel.CRITICAL, 3));
        assertFalse(MobAiPolicy.shouldSkipTick(32.0 * 32.0, threshold, StressLevel.CRITICAL, 4));
    }

    @Test
    void testSpatialIntraTickMoveTeleportDimensionDeathRespawnAndReconnect() {
        PlayerSpatialIndex spatial = PlayerSpatialIndex.forCurrentThread();
        Object overworld = new Object();
        Object nether = new Object();

        // Build index at tick 100: Player 1 at (80, 64, 80) -> far from mob at (0, 64, 0)
        spatial.beginFrame(overworld, 100L);
        spatial.addPlayer(1, 80.0, 64.0, 80.0);
        assertEquals(Double.MAX_VALUE, spatial.findNearestDistanceSq(overworld, 0, 64, 0, 32.0));

        // SAME TICK (tick 100!): Player teleports to (10, 64, 0) -> intra-tick incremental move MUST take effect immediately!
        spatial.updatePlayer(1, overworld, 10.0, 64.0, 0.0, false);
        double distSqIntraTick = spatial.findNearestDistanceSq(overworld, 0, 64, 0, 32.0);
        assertEquals(100.0, distSqIntraTick, 0.001, "Intra-tick teleport must reflect immediately on same tick");
        assertFalse(MobAiPolicy.shouldSkipTick(distSqIntraTick, 32.0, StressLevel.CRITICAL, 1));

        // Dimension change mid-tick (Overworld -> Nether) must remove player from Overworld index!
        spatial.updatePlayer(1, nether, 10.0, 64.0, 0.0, false);
        assertFalse(spatial.containsPlayer(1));
        assertEquals(Double.MAX_VALUE, spatial.findNearestDistanceSq(overworld, 0, 64, 0, 32.0));

        // Reconnect / Respawn back into Overworld at spawn (12, 64, 0)
        spatial.updatePlayer(1, overworld, 12.0, 64.0, 0.0, false);
        assertTrue(spatial.containsPlayer(1));
        assertEquals(144.0, spatial.findNearestDistanceSq(overworld, 0, 64, 0, 32.0), 0.001);

        // Death / Disconnect removes cleanly with zero ghost entries
        spatial.removePlayer(1);
        assertEquals(0, spatial.getIndexedPlayerCount());
        assertEquals(Double.MAX_VALUE, spatial.findNearestDistanceSq(overworld, 0, 64, 0, 32.0));
    }

    @Test
    void testSpatialNegativeCoordsBucketBoundariesAndMultiPlayer() {
        PlayerSpatialIndex spatial = PlayerSpatialIndex.forCurrentThread();
        Object world = new Object();

        spatial.beginFrame(world, 50L);
        // Player 1 on negative side right next to bucket boundary x=-32.5, mob at x=-31.5 -> cross bucket border gap = 1.0 block!
        spatial.addPlayer(1, -32.5, 64.0, -0.5);
        // Player 2 in distant positive bucket
        spatial.addPlayer(2, 120.0, 64.0, 120.0);
        // Player 3 near x=31.9 -> z=32.1 border
        spatial.addPlayer(3, 31.9, 64.0, 32.1);

        // Mob across -32 bucket seam at (-31.5, 64, -0.5) must find Player 1 cleanly!
        assertEquals(1.0, spatial.findNearestDistanceSq(world, -31.5, 64.0, -0.5, 32.0), 0.001);

        // Mob near (32.5, 64, 31.5) must see Player 3 across both X and Z 32-block bucket boundaries!
        double expected = (32.5 - 31.9) * (32.5 - 31.9) + (31.5 - 32.1) * (31.5 - 32.1);
        assertEquals(expected, spatial.findNearestDistanceSq(world, 32.5, 64.0, 31.5, 32.0), 0.001);

        // Player 1 walks across boundary cleanly without leaving ghost in old bucket
        spatial.updatePlayer(1, world, -100.0, 64.0, -100.0, false);
        assertEquals(Double.MAX_VALUE, spatial.findNearestDistanceSq(world, -31.5, 64.0, -0.5, 32.0));
    }

    @Test
    void testSaddledEntitiesWithoutPassengerVillagersGolemsTamedLeashedBreedingAndModded() {
        // 1. Pig / Strider with saddle equipped WITHOUT a passenger (hasPassengers = false) MUST be protected!
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, true, false, false, false, false, false),
            "Saddled pig or strider without passenger must be fully protected");

        // 2. Saddle + passenger also protected
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, true, true, false, false, false, false));

        // 3. Leashed, breeding, tamed, custom name, riding, combat protected
        assertTrue(MobAiPolicy.hasDynamicProtection(true, false, false, false, false, false, false, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, true, false, false, false, false, false, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, false, false, true, false, false, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, false, false, false, true, false, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, false, false, false, false, true, false));
        assertTrue(MobAiPolicy.hasDynamicProtection(false, false, false, false, false, false, false, true));

        // 4. Category protections: Villagers, Golems, Modded Entities, Non-Passive
        assertFalse(MobAiPolicy.isEligibleCategory(true, true, false, true, true, true), "Villagers/Merchants protected");
        assertFalse(MobAiPolicy.isEligibleCategory(true, false, true, true, true, true), "Golems protected");
        assertFalse(MobAiPolicy.isEligibleCategory(true, false, false, false, true, true), "Modded entities protected");
        assertFalse(MobAiPolicy.isEligibleCategory(false, false, false, true, true, true), "Hostile/non-passive protected");

        // Standard wild cow eligible
        assertTrue(MobAiPolicy.isEligibleCategory(true, false, false, true, true, true));
        assertFalse(MobAiPolicy.hasDynamicProtection(false, false, false, false, false, false, false, false));
    }
}
