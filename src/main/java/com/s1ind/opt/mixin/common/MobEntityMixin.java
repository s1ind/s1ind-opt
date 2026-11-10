package com.s1ind.opt.mixin.common;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.config.S1indConfig;
import com.s1ind.opt.engine.AdaptivePerformanceEngine;
import com.s1ind.opt.engine.StressLevel;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Heuristic-guarded AI throttling strictly for vanilla passive entities.
 *
 * Guarantees & Safety Guards:
 * - Zero stale distance cache: evaluates exact squared player distance every tick
 *   via allocation-free indexed loop so approaching within 16 blocks restores
 *   vanilla AI on the very same tick.
 * - Never throttles: hostile mobs, villagers/merchants (by default), third-party
 *   modded entities (by default), tamed pets, custom-named, leashed, riding,
 *   breeding animals, or mobs actively panicking/in combat.
 */
@Mixin(MobEntity.class)
public abstract class MobEntityMixin extends LivingEntity {

    private static final double INTERACTION_RADIUS_SQ = 16.0 * 16.0; // 256.0

    protected MobEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    @Shadow
    public abstract LivingEntity getTarget();

    @Unique
    private int s1ind$skipCounter = 0;

    @Unique
    private Boolean s1ind$isEligibleVanillaPassive = null;

    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    private void s1ind$throttlePassiveAiSafely(CallbackInfo ci) {
        S1indConfig cfg = S1indOptMod.config();
        if (cfg == null || !cfg.throttleDistantPassiveMobAi()) {
            return;
        }

        // One-time entity eligibility check per lifetime
        if (s1ind$isEligibleVanillaPassive == null) {
            if (!((Object) this instanceof PassiveEntity)) {
                s1ind$isEligibleVanillaPassive = false;
                return;
            }
            if (cfg.protectVillagerAi() && (Object) this instanceof MerchantEntity) {
                s1ind$isEligibleVanillaPassive = false;
                return;
            }
            if (cfg.protectModdedEntities()) {
                Identifier id = EntityType.getId(this.getType());
                if (id == null || !"minecraft".equals(id.getNamespace())) {
                    s1ind$isEligibleVanillaPassive = false;
                    return;
                }
            }
            s1ind$isEligibleVanillaPassive = true;
        }

        if (!s1ind$isEligibleVanillaPassive) {
            return;
        }

        // Dynamic gameplay safety guards
        if (this.hasCustomName()) return;

        MobEntity self = (MobEntity) (Object) this;
        if (self.isLeashed() || this.hasPassengers() || this.hasVehicle()) return;

        if ((Object) this instanceof TameableEntity tameable && tameable.isTamed()) return;
        if ((Object) this instanceof AnimalEntity animal && animal.isInLove()) return;
        if (this.getTarget() != null || this.getAttacker() != null) return;

        World world = this.getEntityWorld();
        if (world == null) return;

        // Instant zero-allocation squared-distance check across non-spectator players
        double minDistSq = Double.MAX_VALUE;
        List<? extends PlayerEntity> players = world.getPlayers();
        for (int i = 0, len = players.size(); i < len; i++) {
            PlayerEntity p = players.get(i);
            if (!p.isSpectator()) {
                double dSq = this.squaredDistanceTo(p);
                if (dSq < minDistSq) {
                    minDistSq = dSq;
                    if (minDistSq <= INTERACTION_RADIUS_SQ) {
                        return; // Instant exit within 16 blocks: 0-tick lag guarantee
                    }
                }
            }
        }

        double threshold = cfg.passiveMobDistanceThreshold();
        double thresholdSq = threshold * threshold;
        StressLevel stress = cfg.enableAdaptiveEngine()
            ? AdaptivePerformanceEngine.getInstance().getStressLevel()
            : StressLevel.NORMAL;

        s1ind$skipCounter++;

        // Intermediate zone: 16 to threshold blocks
        if (minDistSq < thresholdSq) {
            if (stress == StressLevel.CRITICAL && (s1ind$skipCounter & 1) != 0) {
                ci.cancel();
            }
            return;
        }

        // Distant zone: > threshold blocks
        switch (stress) {
            case CRITICAL -> {
                if ((s1ind$skipCounter & 3) != 0) {
                    ci.cancel(); // Skip 3 of 4
                }
            }
            case MODERATE -> {
                if ((s1ind$skipCounter % 3) != 0) {
                    ci.cancel(); // Skip 2 of 3
                }
            }
            default -> {
                if ((s1ind$skipCounter & 1) != 0) {
                    ci.cancel(); // Skip 1 of 2
                }
            }
        }
    }
}
