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
 * Uploads the terrain meshes of all client worlds at the point in the frame where VulkanMod uploads
 * its own.
 *
 * <p>{@code preInitFrame} runs before the render pass of the frame begins and before the staging
 * buffers are flushed, which is the only moment a buffer upload is allowed in. Injecting at the call
 * that VulkanMod itself uses for that puts the replacement drain in exactly the place its own drain
 * would have been.</p>
 */
@Pseudo
@Mixin(targets = "net.vulkanmod.vulkan.Renderer", remap = false)
public class MixinVulkanModRenderer {
    
    @Inject(method = "preInitFrame", at = @At("HEAD"))
    private void ip_beginFrame(CallbackInfo ci) {
        VulkanTerrainBridge.beginFrame();
    }
    
    @Inject(
        method = "preInitFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/vulkanmod/render/chunk/WorldRenderer;uploadSections()V"
        )
    )
    private void ip_drainAllDimensions(CallbackInfo ci) {
        VulkanTerrainBridge.drainSectionUploads();
    }
    
    /**
     * Watches the one call that writes VulkanMod's viewport, because that is where the Y flip of a
     * render target lives: a GL request arrives with a positive height and is recorded negated, while
     * {@code setInvertedViewport} gets here with a negative one, which cancels the flip and draws the
     * target top to bottom. Every path that rebinds a frame buffer mid pass goes through it, so the
     * sign of the last write says how the next terrain layer would be drawn.
     */
    @Inject(method = "setViewport(IIII)V", at = @At("HEAD"))
    private static void ip_noteViewportWrite(
        int x, int y, int width, int height, CallbackInfo ci
    ) {
        VulkanTerrainBridge.noteViewportWrite(x, y, width, height);
    }
}
