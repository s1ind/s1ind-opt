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
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Heuristic-guarded AI throttling strictly for vanilla passive entities.
 *
 * Explicit Exclusions (Never throttled):
 * - All hostile mobs (Zombies, Creepers, Skeletons, etc.)
 * - Third-party modded entities (if protectModdedEntities=true)
 * - Villagers & Wandering Traders (MerchantEntity, if protectVillagerAi=true)
 * - Tamed pets (TameableEntity.isTamed)
 * - Custom named mobs (hasCustomName)
 * - Leashed or mounted/riding mobs
 * - Breeding animals (isInLove)
 * - Mobs panicking or actively targeted in combat
 * - Entities within 16 blocks of a player
 */
@Mixin(MobEntity.class)
public abstract class MobEntityMixin extends LivingEntity {

    protected MobEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    @Shadow
    public abstract LivingEntity getTarget();

    @Unique
    private int s1ind$skipCounter = 0;

    @Unique
    private double s1ind$cachedPlayerDistance = 0.0;

    @Unique
    private Boolean s1ind$isEligibleVanillaPassive = null;

    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    private void s1ind$throttlePassiveAiSafely(CallbackInfo ci) {
        S1indConfig cfg = S1indOptMod.config();
        if (cfg == null || !cfg.throttleDistantPassiveMobAi()) {
            return;
        }

        // One-time eligibility check per entity lifetime (zero repeat allocations)
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

        // Refresh player distance every 32 ticks (spread by entity ID)
        if (((this.age + this.getId()) & 31) == 0) {
            World world = this.getEntityWorld();
            if (world != null) {
                var player = world.getClosestPlayer(this, 128.0);
                s1ind$cachedPlayerDistance = player != null ? Math.sqrt(this.squaredDistanceTo(player)) : 999.0;
            }
        }

        // Interaction zone (< 16 blocks): 100% vanilla fidelity
        if (s1ind$cachedPlayerDistance < 16.0) {
            return;
        }

        double threshold = cfg.passiveMobDistanceThreshold();
        StressLevel stress = cfg.enableAdaptiveEngine()
            ? AdaptivePerformanceEngine.getInstance().getStressLevel()
            : StressLevel.NORMAL;

        s1ind$skipCounter++;

        // Intermediate zone: 16 to threshold blocks
        if (s1ind$cachedPlayerDistance < threshold) {
            if (stress == StressLevel.CRITICAL && (s1ind$skipCounter & 1) != 0) {
                ci.cancel();
            }
            return;
        }

        // Distant zone: > threshold blocks (default 32+)
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
