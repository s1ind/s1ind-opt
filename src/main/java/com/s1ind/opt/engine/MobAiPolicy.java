package com.s1ind.opt.engine;

/**
 * Pure heuristic policy deciding whether a passive mob AI tick may be skipped.
 *
 * Strictly enforces:
 * - Zero throttling within <= 16.0 blocks (including exact 16.0 boundary).
 * - Conservative throttling in 16..threshold range only under CRITICAL load.
 * - Tiered shedding beyond threshold (NORMAL: 1/2, MODERATE: 2/3, CRITICAL: 3/4).
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
        boolean hasPassengers,
        boolean hasVehicle,
        boolean isTamed,
        boolean isInLove,
        boolean hasTargetOrAttacker
    ) {
        return hasCustomName
            || isLeashed
            || hasPassengers
            || hasVehicle
            || isTamed
            || isInLove
            || hasTargetOrAttacker;
    }

    /**
     * Computes whether this AI tick should be skipped given the closest non-spectator player squared distance.
     */
    public static boolean shouldSkipTick(
        double minDistSq,
        double thresholdBlocks,
        StressLevel stress,
        int skipCounter
    ) {
        // Guaranteed interaction zone: <= 16.0 blocks is NEVER throttled
        if (minDistSq <= INTERACTION_RADIUS_SQ) {
            return false;
        }

        double thresholdSq = thresholdBlocks * thresholdBlocks;

        // Intermediate zone: (16, threshold) blocks
        if (minDistSq < thresholdSq) {
            return stress == StressLevel.CRITICAL && (skipCounter & 1) != 0;
        }

        // Far zone: >= threshold blocks
        return switch (stress) {
            case CRITICAL -> (skipCounter & 3) != 0; // Skip 3 of 4
            case MODERATE -> (skipCounter % 3) != 0; // Skip 2 of 3
            case NORMAL   -> (skipCounter & 1) != 0; // Skip 1 of 2
        };
    }
}
