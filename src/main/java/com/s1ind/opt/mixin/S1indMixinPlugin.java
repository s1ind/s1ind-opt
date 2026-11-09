package com.s1ind.opt.mixin;

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
 * Verifies runtime environment and installed performance mods (Sodium, ImmediatelyFast, Lithium, Iris).
 * All S1ind mixins target non-conflicting hooks (util threads, passive entity AI, client run entry, light empty check).
 */
public class S1indMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("s1ind_opt/compat");

    @Override
    public void onLoad(String mixinPackage) {
        FabricLoader loader = FabricLoader.getInstance();
        boolean sodium = loader.isModLoaded("sodium") || loader.isModLoaded("embeddium");
        boolean immediatelyFast = loader.isModLoaded("immediatelyfast");
        boolean lithium = loader.isModLoaded("lithium");
        boolean iris = loader.isModLoaded("iris");

        LOG.info("[S1ind] Environment check: Sodium={}, ImmediatelyFast={}, Lithium={}, Iris={}",
            sodium, immediatelyFast, lithium, iris);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
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
