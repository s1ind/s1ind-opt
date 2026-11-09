package com.s1ind.opt.mixin.client;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.engine.PerformanceTelemetry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.DebugHud;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Injects real-time 1% Low, 0.1% Low, and MSPT metrics into the Minecraft F3 debug screen.
 */
@Mixin(DebugHud.class)
public abstract class DebugHudMixin {

    @Inject(method = "drawText", at = @At("HEAD"))
    private void s1ind$injectTelemetryToF3(DrawContext context, List<String> text, boolean left, CallbackInfo ci) {
        if (left && S1indOptMod.config() != null && S1indOptMod.config().enableF3Telemetry()) {
            text.add("");
            text.add(Formatting.GOLD + "[S1ind APE v3.0] " + Formatting.WHITE + PerformanceTelemetry.formatSummary());
        }
    }
}
