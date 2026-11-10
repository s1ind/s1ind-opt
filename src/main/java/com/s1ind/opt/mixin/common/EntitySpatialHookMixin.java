package com.s1ind.opt.mixin.common;

import com.s1ind.opt.engine.PlayerSpatialIndex;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps PlayerSpatialIndex instantaneously synchronized when server players walk,
 * teleport, respawn, die, disconnect, or change dimensions within the same tick.
 */
@Mixin(Entity.class)
public abstract class EntitySpatialHookMixin {

    @Shadow
    public abstract World getEntityWorld();

    @Shadow
    public abstract int getId();

    @Inject(method = "setPos", at = @At("RETURN"))
    private void s1ind$onPlayerMoved(double x, double y, double z, CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player) {
            World world = this.getEntityWorld();
            if (world != null && !world.isClient()) {
                PlayerSpatialIndex.forCurrentThread().updatePlayer(
                    this.getId(),
                    world,
                    x,
                    y,
                    z,
                    player.isSpectator()
                );
            }
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void s1ind$onPlayerRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity) {
            World world = this.getEntityWorld();
            if (world != null && !world.isClient()) {
                PlayerSpatialIndex.forCurrentThread().removePlayer(this.getId());
            }
        }
    }
}
