// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.compat.mixin.vulkanmod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.vulkanmod_compatibility.VulkanTerrainBridge;

/**
 * Keeps terrain where Immersive Portals put it.
 *
 * <p>VulkanMod's terrain pass starts by calling {@code rebindMainTarget}, which for OpenGL would be
 * a no-op but here ends the currently bound render pass and begins the one drawing into the window.
 * The portal content of another dimension is rendered into a separate framebuffer, so following that
 * call would draw the other world's terrain straight onto the screen instead.</p>
 */
@Pseudo
@Mixin(targets = "net.vulkanmod.vulkan.pass.DefaultMainPass", remap = false)
public class MixinVulkanModDefaultMainPass {
    
    @Inject(method = "rebindMainTarget", at = @At("HEAD"), cancellable = true)
    private void ip_stayOnPortalTarget(CallbackInfo ci) {
        if (VulkanTerrainBridge.isRenderingOffscreen()) {
            ci.cancel();
        }
    }
}
