package qouteall.imm_ptl.core;

import net.fabricmc.loader.api.FabricLoader;
import qouteall.imm_ptl.core.compat.RenderBackendCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class IPMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    
    }
    
    @Override
    public String getRefMapperConfig() {
        return null;
    }
    
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (FabricLoader.getInstance().isModLoaded("porting_lib")) {
            if (mixinClassName.contains("MixinRenderTarget") || mixinClassName.contains("MixinMainTarget")) {
                return false;
            }
        }
        
        if (RenderBackendCompat.IS_NON_GL_RENDERER) {
            // A non-OpenGL renderer (VulkanMod) replaces Minecraft's MainTarget with its own
            // implementation. MixinMainTarget only rewrites the depth attachment format to a
            // depth-stencil format, which is meaningless (and unsupported) on that backend.
            // NOTE: MixinRenderTarget must stay applied, because it provides the IEFrameBuffer
            // duck interface that IPPortingLibCompat casts to.
            if (mixinClassName.contains("MixinMainTarget")) {
                return false;
            }
            
            // MixinProgram @Redirects IOUtils.toString inside Program.compileShaderInternal.
            // VulkanMod's ProgramMixin overwrites that whole method, so the redirect target
            // no longer exists. Mixin treats that as a hard error and aborts the whole
            // transformation of net.minecraft.client.renderer.ShaderInstance's Program class,
            // which made resource loading hang forever on the Mojang loading screen.
            // Skipping it only means the shader-source transformation (the portal clipping
            // equation uniform) is unavailable; FrontClipping already handles a missing uniform.
            if (mixinClassName.endsWith("MixinProgram")) {
                return false;
            }
            
            // Same kind of conflict: @Redirect on ShaderInstance.getOrCreate which VulkanMod
            // rewrites. It is an Iris-only optimization anyway.
            if (mixinClassName.endsWith("MixinShaderInstanceForIris")) {
                return false;
            }
            
            // MixinLevelRenderer_Optional @Redirects into LevelRenderer.renderSectionLayer
            // (method_3251) and LevelRenderer.setupRender (method_3273), both of which
            // VulkanMod @Overwrites. A @Redirect into a method overwritten by another mixin is
            // a hard Mixin error and would make LevelRenderer fail to load (game breaks as
            // soon as a world is entered). This mixin is optional anyway.
            if (mixinClassName.endsWith("MixinLevelRenderer_Optional")) {
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
