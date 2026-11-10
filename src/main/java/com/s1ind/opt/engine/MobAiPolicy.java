package com.s1ind.opt.engine;

/**
 * Pure heuristic policy deciding whether a passive mob AI tick may be skipped.
 *
 * Enforces:
 * - Strict protection <= 16.0 blocks (including exact 16.0 boundary).
 * - Protection of saddled entities independently of passenger presence.
 * - Protection of villagers/merchants, golems, tamed, leashed, riding, breeding,
 *   combat/panicking, custom-named, and modded entities.
 */
public final class MobAiPolicy {

    public static final double INTERACTION_RADIUS_BLOCKS = 16.0;
    public static final double INTERACTION_RADIUS_SQ = INTERACTION_RADIUS_BLOCKS * INTERACTION_RADIUS_BLOCKS; // 256.0

    private MobAiPolicy() {}

    public static boolean isEligibleCategory(
        boolean isPassive,
        boolean isMerchantOrVillager,
        boolean isGolem,
        boolean isVanillaNamespace,
        boolean protectVillagerAi,
        boolean protectModdedEntities
    ) {
        if (!isPassive || isGolem) {
            return false;
        }
        if (protectVillagerAi && isMerchantOrVillager) {
            return false;
        }
        if (protectModdedEntities && !isVanillaNamespace) {
            return false;
        }
        return true;
    }

    public static boolean hasDynamicProtection(
        boolean hasCustomName,
        boolean isLeashed,
        boolean hasSaddleEquipped,
        boolean hasPassengers,
        boolean hasVehicle,
        boolean isTamed,
        boolean isInLove,
        boolean hasTargetOrAttacker
    ) {
        return hasCustomName
            || isLeashed
            || hasSaddleEquipped
            || hasPassengers
            || hasVehicle
            || isTamed
            || isInLove
            || hasTargetOrAttacker;
    }

    /**
     * Computes whether this AI tick should be skipped given closest non-spectator player squared distance.
     */
    public static boolean shouldSkipTick(
        double minDistSq,
        double thresholdBlocks,
        StressLevel stress,
        int skipCounter
    ) {
        if (minDistSq <= INTERACTION_RADIUS_SQ) {
            return false;
        }

        double thresholdSq = thresholdBlocks * thresholdBlocks;

        if (minDistSq < thresholdSq) {
            return stress == StressLevel.CRITICAL && (skipCounter & 1) != 0;
        }

        return switch (stress) {
            case CRITICAL -> (skipCounter & 3) != 0; // Skip 3 of 4
            case MODERATE -> (skipCounter % 3) != 0; // Skip 2 of 3
            case NORMAL   -> (skipCounter & 1) != 0; // Skip 1 of 2
        };
    }
}
