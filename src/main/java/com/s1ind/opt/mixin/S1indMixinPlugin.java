package com.s1ind.opt.mixin;

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
 * Dynamically disables mixins when:
 * 1. Disabled by user in config/s1ind_opt.json.
 * 2. Conflicting third-party mods are present (e.g. custom light engines like Starlight/Phosphor).
 */
public class S1indMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("s1ind_opt/compat");
    private S1indConfig config;
    private boolean starlightOrPhosphorLoaded = false;
    private boolean lithiumLoaded = false;

    @Override
    public void onLoad(String mixinPackage) {
        FabricLoader loader = FabricLoader.getInstance();
        this.config = S1indConfig.load();

        this.starlightOrPhosphorLoaded = loader.isModLoaded("starlight") || loader.isModLoaded("phosphor");
        this.lithiumLoaded = loader.isModLoaded("lithium");
        boolean sodium = loader.isModLoaded("sodium") || loader.isModLoaded("embeddium");
        boolean immediatelyFast = loader.isModLoaded("immediatelyfast");
        boolean iris = loader.isModLoaded("iris");

        LOG.info("[S1ind] Environment audit: Sodium={}, ImmediatelyFast={}, Lithium={}, Iris={}, Starlight/Phosphor={}",
            sodium, immediatelyFast, lithiumLoaded, iris, starlightOrPhosphorLoaded);
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

        // Disable lighting mixin if third-party lighting rewrite is present or disabled in config
        if (mixinClassName.endsWith(".LightingProviderMixin")) {
            if (starlightOrPhosphorLoaded) {
                LOG.info("[S1ind] Disabling LightingProviderMixin due to presence of Starlight/Phosphor.");
                return false;
            }
            if (!config.fastLightUpdates()) {
                LOG.info("[S1ind] Disabling LightingProviderMixin via config.");
                return false;
            }
        }

        // Disable mob entity AI throttling if disabled in config
        if (mixinClassName.endsWith(".MobEntityMixin")) {
            if (!config.throttleDistantPassiveMobAi()) {
                LOG.info("[S1ind] Disabling MobEntityMixin via config.");
                return false;
            }
        }

        // Disable render thread priority boost if disabled in config
        if (mixinClassName.endsWith(".MinecraftClientMixin")) {
            if (!config.boostRenderThreadPriority()) {
                LOG.info("[S1ind] Disabling MinecraftClientMixin via config.");
                return false;
            }
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
