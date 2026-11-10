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
 * Dynamically disables non-telemetry mixins when disabled in config or when
 * conflicting third-party rewrites (e.g. Starlight/Phosphor) are present.
 * Note: MinecraftClientMixin always loads to maintain frame telemetry independent of thread priority config.
 */
public class S1indMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("s1ind_opt/compat");
    private S1indConfig config;
    private boolean starlightOrPhosphorLoaded = false;

    @Override
    public void onLoad(String mixinPackage) {
        FabricLoader loader = FabricLoader.getInstance();
        this.config = S1indConfig.load();
        this.starlightOrPhosphorLoaded = loader.isModLoaded("starlight") || loader.isModLoaded("phosphor");

        boolean sodium = loader.isModLoaded("sodium") || loader.isModLoaded("embeddium");
        boolean immediatelyFast = loader.isModLoaded("immediatelyfast");
        boolean lithium = loader.isModLoaded("lithium");
        boolean iris = loader.isModLoaded("iris");

        LOG.info("[S1ind] Environment audit: Sodium={}, ImmediatelyFast={}, Lithium={}, Iris={}, Starlight/Phosphor={}",
            sodium, immediatelyFast, lithium, iris, starlightOrPhosphorLoaded);
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

        if (mixinClassName.endsWith(".LightingProviderMixin")) {
            if (starlightOrPhosphorLoaded) {
                LOG.info("[S1ind] Disabling LightingProviderMixin due to Starlight/Phosphor.");
                return false;
            }
            if (!config.fastLightUpdates()) {
                LOG.info("[S1ind] Disabling LightingProviderMixin via config/profile.");
                return false;
            }
        }

        if (mixinClassName.endsWith(".MobEntityMixin")) {
            if (!config.throttleDistantPassiveMobAi()) {
                LOG.info("[S1ind] Disabling MobEntityMixin via config/profile.");
                return false;
            }
        }

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
