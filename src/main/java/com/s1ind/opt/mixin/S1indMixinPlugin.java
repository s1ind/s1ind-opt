package com.s1ind.opt.mixin;

import com.s1ind.opt.config.PerformanceProfile;
import com.s1ind.opt.config.S1indConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin configuration plugin.
 *
 * Inspects installed optimization mods (Sodium, Iris, ImmediatelyFast, Lithium, EntityCulling, FerriteCore, ModernFix)
 * and selectively disables conflicting or profile-excluded S1ind mixins without disabling telemetry.
 */
public class S1indMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("s1ind_opt/compat");
    private S1indConfig config;
    private boolean customLightModLoaded = false;

    @Override
    public void onLoad(String mixinPackage) {
        FabricLoader loader = FabricLoader.getInstance();
        this.config = S1indConfig.load();

        this.customLightModLoaded = loader.isModLoaded("starlight") || loader.isModLoaded("phosphor");
        boolean sodium = loader.isModLoaded("sodium") || loader.isModLoaded("embeddium");
        boolean iris = loader.isModLoaded("iris");
        boolean immediatelyFast = loader.isModLoaded("immediatelyfast");
        boolean lithium = loader.isModLoaded("lithium");
        boolean entityCulling = loader.isModLoaded("entityculling");
        boolean ferriteCore = loader.isModLoaded("ferritecore");
        boolean modernFix = loader.isModLoaded("modernfix");

        LOG.info(
            "[S1ind] Environment audit: Profile={}, Sodium={}, Iris={}, ImmediatelyFast={}, Lithium={}, EntityCulling={}, FerriteCore={}, ModernFix={}, CustomLight={}",
            config.profile(), sodium, iris, immediatelyFast, lithium, entityCulling, ferriteCore, modernFix, customLightModLoaded
        );
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (config == null) {
            return true;
        }

        // Lighting fast-path skips if third-party lighting engine is active or profile/config disables it
        if (mixinClassName.endsWith(".LightingProviderMixin")) {
            if (customLightModLoaded) {
                LOG.info("[S1ind] Skipping LightingProviderMixin (Starlight/Phosphor detected).");
                return false;
            }
            if (!config.fastLightUpdates()) {
                LOG.info("[S1ind] Skipping LightingProviderMixin via profile/config.");
                return false;
            }
        }

        // Mob AI throttling and its spatial hook skipped cleanly if disabled
        if (mixinClassName.endsWith(".MobEntityMixin") || mixinClassName.endsWith(".EntitySpatialHookMixin")) {
            if (!config.throttleDistantPassiveMobAi()) {
                LOG.info("[S1ind] Skipping {} via profile/config.", mixinClassName);
                return false;
            }
        }

        // Render priority elevation isolated cleanly in ClientPriorityMixin
        if (mixinClassName.endsWith(".ClientPriorityMixin")) {
            if (!config.boostRenderThreadPriority()) {
                LOG.info("[S1ind] Skipping ClientPriorityMixin via profile/config.");
                return false;
            }
        }

        // Thread pool overrides skipped cleanly in COMPATIBILITY profile
        if (mixinClassName.endsWith(".UtilMixin")) {
            if (config.profile() == PerformanceProfile.COMPATIBILITY) {
                LOG.info("[S1ind] Skipping UtilMixin in COMPATIBILITY profile.");
                return false;
            }
        }

        // MinecraftClientMixin, MinecraftServerMixin, DebugHudMixin always load for telemetry/lifecycle
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
