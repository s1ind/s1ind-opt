package com.s1ind.opt.mixin.common;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.config.S1indConfig;
import com.s1ind.opt.engine.AdaptivePerformanceEngine;
import com.s1ind.opt.engine.MobAiPolicy;
import com.s1ind.opt.engine.PlayerSpatialIndex;
import com.s1ind.opt.engine.StressLevel;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.GolemEntity;
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
 * Adaptive, heuristic-guarded AI throttling strictly for vanilla passive entities.
 *
 * Delegates policy evaluation to MobAiPolicy and spatial acceleration (when >8 players)
 * to PlayerSpatialIndex, preserving immediate 0-tick response when approaching <=16 blocks.
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
    private Boolean s1ind$eligibleCategory = null;

    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    private void s1ind$throttlePassiveAiSafely(CallbackInfo ci) {
        S1indConfig cfg = S1indOptMod.config();
        if (cfg == null || !cfg.throttleDistantPassiveMobAi()) {
            return;
        }

        if (s1ind$eligibleCategory == null) {
            Identifier id = EntityType.getId(this.getType());
            boolean isVanilla = id != null && "minecraft".equals(id.getNamespace());
            s1ind$eligibleCategory = MobAiPolicy.isEligibleCategory(
                (Object) this instanceof PassiveEntity,
                (Object) this instanceof MerchantEntity,
                (Object) this instanceof GolemEntity,
                isVanilla,
                cfg.protectVillagerAi(),
                cfg.protectModdedEntities()
            );
        }

        if (!s1ind$eligibleCategory) {
            return;
        }

        MobEntity self = (MobEntity) (Object) this;
        boolean protectedNow = MobAiPolicy.hasDynamicProtection(
            this.hasCustomName(),
            self.isLeashed(),
            this.hasPassengers(),
            this.hasVehicle(),
            (Object) this instanceof TameableEntity tameable && tameable.isTamed(),
            (Object) this instanceof AnimalEntity animal && animal.isInLove(),
            this.getTarget() != null || this.getAttacker() != null
        );
        if (protectedNow) {
            return;
        }

        World world = this.getEntityWorld();
        if (world == null) {
            return;
        }

        double threshold = cfg.passiveMobDistanceThreshold();
        double minDistSq = s1ind$resolveNearestPlayerDistanceSq(world, threshold);

        StressLevel stress = cfg.enableAdaptiveEngine()
            ? AdaptivePerformanceEngine.getInstance().getStressLevel()
            : StressLevel.NORMAL;

        s1ind$skipCounter++;
        if (MobAiPolicy.shouldSkipTick(minDistSq, threshold, stress, s1ind$skipCounter)) {
            ci.cancel();
        }
    }

    @Unique
    private double s1ind$resolveNearestPlayerDistanceSq(World world, double searchRadiusBlocks) {
        List<? extends PlayerEntity> players = world.getPlayers();
        int playerCount = players.size();
        if (playerCount == 0) {
            return Double.MAX_VALUE;
        }

        // Fast zero-allocation direct path for singleplayer & small servers
        if (playerCount <= PlayerSpatialIndex.DIRECT_SCAN_THRESHOLD) {
            double minDistSq = Double.MAX_VALUE;
            for (int i = 0; i < playerCount; i++) {
                PlayerEntity p = players.get(i);
                if (!p.isSpectator()) {
                    double dSq = this.squaredDistanceTo(p);
                    if (dSq < minDistSq) {
                        minDistSq = dSq;
                        if (minDistSq <= MobAiPolicy.INTERACTION_RADIUS_SQ) {
                            return minDistSq;
                        }
                    }
                }
            }
            return minDistSq;
        }

        // Spatial chunk-bucket path for high-population multiplayer servers (>8 players)
        PlayerSpatialIndex spatial = PlayerSpatialIndex.forCurrentThread();
        long worldTime = world.getTime();
        if (spatial.needsRebuild(world, worldTime)) {
            spatial.beginFrame(world, worldTime);
            for (int i = 0; i < playerCount; i++) {
                PlayerEntity p = players.get(i);
                if (!p.isSpectator()) {
                    spatial.addPlayer(p.getX(), p.getY(), p.getZ());
                }
            }
        }

        return spatial.findNearestDistanceSq(this.getX(), this.getY(), this.getZ(), searchRadiusBlocks);
    }
}
