// Modified for the unofficial VulkanMod fork of Immersive Portals (see NOTICE).
package qouteall.imm_ptl.core.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class IPCompatMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("imm_ptl_compat");
    
    /** Whether the cloud probe's decision has already been written to the log. */
    private static boolean loggedCloudProbeDecision = false;
    
    @Override
    public void onLoad(String mixinPackage) {
    
    }
    
    @Override
    public String getRefMapperConfig() {
        return null;
    }
    
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        
        
        FabricLoader fabricLoader = FabricLoader.getInstance();
        if (mixinClassName.contains("CloudRenderer") && !loggedCloudProbeDecision) {
            // the probe itself logs nothing when the cloud path is not reached, so record here that
            // the mixin was seen, and with which decision
            loggedCloudProbeDecision = true;
            LOGGER.info(String.format(
                "[cloud probe] compat plugin saw %s for target %s, VulkanMod loaded = %s",
                mixinClassName, targetClassName,
                fabricLoader.isModLoaded(RenderBackendCompat.VULKAN_MOD_ID)
            ));
        }
        if (mixinClassName.contains("VulkanMod")) {
            return fabricLoader.isModLoaded(RenderBackendCompat.VULKAN_MOD_ID);
        }
        
        if (mixinClassName.contains("IrisSodium")) {
            boolean sodiumLoaded = fabricLoader.isModLoaded("sodium");
            boolean irisLoaded = fabricLoader.isModLoaded("iris");
            return sodiumLoaded && irisLoaded;
        }
        
        if (mixinClassName.contains("Iris")) {
            boolean irisLoaded = fabricLoader.isModLoaded("iris");
            return irisLoaded;
        }
        
        if (mixinClassName.contains("Sodium")) {
            boolean sodiumLoaded = fabricLoader.isModLoaded("sodium");
            return sodiumLoaded;
        }
        
        if (mixinClassName.contains("Flywheel")) {
            boolean flywheelLoaded = fabricLoader.isModLoaded("flywheel");
            return flywheelLoaded;
        }
        
        if (mixinClassName.contains("CardinalComp")) {
            boolean cardinalCompLoaded = fabricLoader.isModLoaded("cardinal-components-base");
            return cardinalCompLoaded;
        }
        
        return false;
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
