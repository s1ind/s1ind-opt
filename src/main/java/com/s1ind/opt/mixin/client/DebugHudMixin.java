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
 * Injects real-time APE metrics (FPS, 1% / 0.1% Low, MSPT, GC pauses, Bottleneck) into F3 HUD.
 */
@Mixin(DebugHud.class)
public abstract class DebugHudMixin {

    @Inject(method = "drawText", at = @At("HEAD"))
    private void s1ind$injectTelemetryToF3(DrawContext context, List<String> text, boolean left, CallbackInfo ci) {
        if (left && S1indOptMod.config() != null && S1indOptMod.config().enableF3Telemetry()) {
            text.add("");
            text.add(Formatting.GOLD + "[S1ind APE v3.1] " + Formatting.WHITE + PerformanceTelemetry.formatLine1());
            text.add(Formatting.AQUA + "[S1ind Metrics] " + Formatting.WHITE + PerformanceTelemetry.formatLine2());
        }
    }
}
