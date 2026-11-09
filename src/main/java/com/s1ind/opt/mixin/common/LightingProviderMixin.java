package com.s1ind.opt.mixin.common;

import com.s1ind.opt.S1indOptMod;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lighting update fast-path:
 *
 * If neither sky light nor block light has pending updates,
 * skips entering provider loops entirely and returns 0 immediately.
 */
@Mixin(LightingProvider.class)
public abstract class LightingProviderMixin {

    @Shadow
    public abstract boolean hasUpdates();

    @Inject(method = "doLightUpdates", at = @At("HEAD"), cancellable = true)
    private void s1ind$fastLightUpdates(CallbackInfoReturnable<Integer> cir) {
        if (S1indOptMod.config() != null && S1indOptMod.config().fastLightUpdates()) {
            if (!this.hasUpdates()) {
                cir.setReturnValue(0);
            }
        }
    }
}
