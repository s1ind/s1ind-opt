package com.s1ind.opt.mixin.common;

import com.s1ind.opt.S1indOptMod;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intelligent AI throttling strictly for PASSIVE entities (animals, villagers, breeding mobs).
 *
 * Hostile mobs (Zombies, Creepers, Skeletons, etc.) are NEVER throttled.
 *
 * Passive entities further than 32 blocks from players skip 2 out of 3 pathfinding
 * updates, eliminating up to 50% CPU load near animal pens, farms, and villages.
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
    private boolean s1ind$isNearPlayer = true;

    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    private void s1ind$throttleDistantPassiveMobAi(CallbackInfo ci) {
        if (S1indOptMod.config() == null || !S1indOptMod.config().throttleDistantPassiveMobAi()) {
            return;
        }

        // STRICT GUARD: Only apply to passive entities (animals, villagers, ambient)
        // Hostile mobs are NEVER throttled and retain 100% vanilla combat AI
        if (!((Object) this instanceof PassiveEntity)) {
            return;
        }

        // Never throttle damaged passive mobs (e.g. running away from player/fire)
        if (this.getTarget() != null || this.getAttacker() != null) {
            s1ind$isNearPlayer = true;
            return;
        }

        // Refresh player distance check once every 32 ticks (staggered by entity ID)
        if (((this.age + this.getId()) & 31) == 0) {
            World world = this.getEntityWorld();
            if (world != null) {
                s1ind$isNearPlayer = world.getClosestPlayer(this, 32.0) != null;
            }
        }

        // If no player is within 32 blocks, skip 2 out of 3 AI ticks
        if (!s1ind$isNearPlayer) {
            s1ind$skipCounter++;
            if ((s1ind$skipCounter % 3) != 0) {
                ci.cancel();
            }
        }
    }
}
