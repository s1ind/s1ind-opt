package com.s1ind.opt.mixin.client;

import com.s1ind.opt.S1indOptMod;
import com.s1ind.opt.engine.PerformanceTelemetry;
import com.s1ind.opt.engine.PerformanceTelemetry.TelemetrySnapshot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Injects pre-built immutable APE telemetry snapshots into F3 Debug HUD.
 * Performs no String.format() and no allocations per rendered frame.
 */
@Mixin(DebugHud.class)
public abstract class DebugHudMixin {

    @Inject(method = "drawText", at = @At("HEAD"))
    private void s1ind$injectTelemetryToF3(DrawContext context, List<String> text, boolean left, CallbackInfo ci) {
        if (left && S1indOptMod.config() != null && S1indOptMod.config().enableF3Telemetry()) {
            TelemetrySnapshot snap = PerformanceTelemetry.getSnapshot();
            text.add("");
            text.add(snap.f3Line1());
            text.add(snap.f3Line2());
            text.add(snap.f3Line3());
        }
    }
}
