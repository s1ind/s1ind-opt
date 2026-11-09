package com.s1ind.opt.mixin.common;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.engine.AdaptivePerformanceEngine;
import com.s1ind.opt.engine.StressLevel;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adaptive AI throttling strictly for PASSIVE entities (animals, villagers, ambient).
 *
 * Guaranteed Safety Guards:
 * - Hostile mobs (Zombies, Creepers, Skeletons, etc.) are NEVER throttled.
 * - Named mobs (custom names / name tags) are NEVER throttled.
 * - Leashed or ridden mobs are NEVER throttled.
 * - Breeding animals (isInLove) are NEVER throttled.
 * - Damaged or combat-active mobs are NEVER throttled.
 * - Near player (< 16 blocks): 0% throttling (100% full vanilla tick rate).
 *
 * Outside 16/32 blocks, throttling scales adaptively based on APE StressLevel:
 * - NORMAL: 50% skip >32 blocks.
 * - MODERATE: 66% skip >32 blocks.
 * - CRITICAL: 75% skip >32 blocks, 50% skip 16-32 blocks.
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

    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    private void s1ind$throttlePassiveAiAdaptively(CallbackInfo ci) {
        if (S1indOptMod.config() == null || !S1indOptMod.config().throttleDistantPassiveMobAi()) {
            return;
        }

        // GUARD 1: Only apply to passive entities
        if (!((Object) this instanceof PassiveEntity)) {
            return;
        }

        // GUARD 2: Preserve named pets/mobs
        if (this.hasCustomName()) {
            return;
        }

        // GUARD 3: Preserve leashed or ridden entities
        MobEntity self = (MobEntity) (Object) this;
        if (self.isLeashed() || this.hasPassengers() || this.hasVehicle()) {
            return;
        }

        // GUARD 4: Preserve breeding animals
        if ((Object) this instanceof AnimalEntity animal && animal.isInLove()) {
            return;
        }

        // GUARD 5: Preserve mobs actively panicking or in combat
        if (this.getTarget() != null || this.getAttacker() != null) {
            return;
        }

        // Update player distance once every 32 ticks (spread by entity ID)
        if (((this.age + this.getId()) & 31) == 0) {
            World world = this.getEntityWorld();
            if (world != null) {
                var player = world.getClosestPlayer(this, 128.0);
                s1ind$cachedPlayerDistance = player != null ? Math.sqrt(this.squaredDistanceTo(player)) : 999.0;
            }
        }

        // Close to player (< 16 blocks): 100% full fidelity
        if (s1ind$cachedPlayerDistance < 16.0) {
            return;
        }

        double threshold = S1indOptMod.config().passiveMobDistanceThreshold();
        StressLevel stress = AdaptivePerformanceEngine.getInstance().getStressLevel();

        s1ind$skipCounter++;

        // Intermediate zone: 16 to 32 blocks
        if (s1ind$cachedPlayerDistance < threshold) {
            if (stress == StressLevel.CRITICAL && (s1ind$skipCounter & 1) != 0) {
                ci.cancel(); // 50% skip under critical load only
            }
            return;
        }

        // Distant zone: > threshold blocks (32+)
        switch (stress) {
            case CRITICAL -> {
                // Skip 3 of 4 ticks (75% reduction under emergency load)
                if ((s1ind$skipCounter & 3) != 0) {
                    ci.cancel();
                }
            }
            case MODERATE -> {
                // Skip 2 of 3 ticks (66% reduction under moderate load)
                if ((s1ind$skipCounter % 3) != 0) {
                    ci.cancel();
                }
            }
            default -> {
                // Skip 1 of 2 ticks (50% reduction under normal load)
                if ((s1ind$skipCounter & 1) != 0) {
                    ci.cancel();
                }
            }
        }
    }
}
