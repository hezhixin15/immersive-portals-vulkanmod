// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.compat.mixin.vulkanmod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.RenderBackendCompat;
import qouteall.imm_ptl.core.compat.vulkanmod_compatibility.VulkanTerrainBridge;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * VulkanMod's terrain renderer is a single shared instance that every {@code LevelRenderer} points
 * at, and it follows {@code LevelRenderer.setLevel} / {@code allChanged} into whatever world those
 * are called with. Immersive Portals calls both on the renderers of dimensions the player is not
 * in, which would rebuild the shared renderer's whole section grid for the wrong world and leave
 * the player staring at an empty sky for about a second.
 *
 * <p>{@link RenderBackendCompat#withVulkanLevelBindingSuppressed(Runnable)} wraps the calls that
 * belong to a world Immersive Portals only ticks; the worlds that are actually drawn get their own
 * terrain renderer instead, see
 * {@link qouteall.imm_ptl.core.compat.vulkanmod_compatibility.VulkanTerrainBridge}.</p>
 */
@Pseudo
@Mixin(targets = "net.vulkanmod.render.chunk.WorldRenderer", remap = false)
public class MixinVulkanModWorldRenderer {
    
    @Inject(method = "setLevel", at = @At("HEAD"), cancellable = true)
    private void ip_cancelLevelBinding(@Coerce Object level, CallbackInfo ci) {
        if (RenderBackendCompat.isVulkanLevelBindingSuppressed()) {
            ci.cancel();
        }
    }
    
    @Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
    private void ip_cancelSectionGridRebuild(CallbackInfo ci) {
        if (RenderBackendCompat.isVulkanLevelBindingSuppressed()) {
            ci.cancel();
        }
    }
    
    /**
     * VulkanMod re-sorts translucent sections from {@code renderSectionLayer}, so it runs for every
     * layer of every pass, and Immersive Portals multiplies the passes by the number of portal views
     * in the frame. Each run schedules up to 200 sort tasks, and each of those allocates a fresh
     * segment in the 120 KB index buffer a whole chunk area shares, freeing the previous segment only
     * a frame later - so a portal view keeps that buffer growing until {@code AreaBuffer.reallocate}
     * copies it through the transfer queue while the graphics queue is already drawing from it.
     *
     * <p>The sort tasks also read the camera from VulkanMod's static renderer instance instead of the
     * one captured when they were created, which under Immersive Portals points at whichever dimension
     * ran a pass last, so a portal view's sections get sorted against the player's own dimension.</p>
     *
     * <p>Immersive Portals has always skipped this while rendering a portal - on the GL renderer
     * {@code MixinLevelRenderer_Optional} returns null for the translucent render type for exactly this
     * reason - but {@link qouteall.imm_ptl.core.IPMixinPlugin} switches that mixin off under Vulkan
     * because it targets methods VulkanMod replaces wholesale, and nothing took its place. Sections
     * keep the sort order their last mesh gave them, which is the same tradeoff the GL path makes.</p>
     */
    @Inject(method = "sortTranslucentSections", at = @At("HEAD"), cancellable = true)
    private void ip_cancelTranslucentSortInPortalView(CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            ci.cancel();
        }
    }
    
    /**
     * Some of VulkanMod's terrain code only reaches the renderer through its shared static instance:
     * the world to mesh from, the render distance, the camera position and the chunk readiness table.
     * Everything else runs on the instance the level renderer holds, so a pass whose two differ makes
     * VulkanMod index one dimension's sections into another dimension's chunks and crash. Every pass
     * comes through here, which is where the shared state is made to describe the renderer running it.
     */
    @Inject(method = "setupRenderer", at = @At("HEAD"))
    private void ip_alignSharedStateWithThisRenderer(CallbackInfo ci) {
        VulkanTerrainBridge.alignWithExecutingRenderer((Object) (Object) this);
    }
    
    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void ip_alignSharedStateForLayer(
        @Coerce Object renderType, double camX, double camY, double camZ,
        @Coerce Object modelView, @Coerce Object projection, CallbackInfo ci
    ) {
        VulkanTerrainBridge.alignWithExecutingRenderer((Object) (Object) this);
        VulkanTerrainBridge.keepTargetForLayer(renderType);
    }
    
    /**
     * VulkanMod clears the indirect draw parameter buffer at the start of every pass, because a pass
     * is normally the whole frame. Immersive Portals runs a full {@code renderLevel} per visible
     * portal inside the frame, so the second pass would reset the offsets recorded by the first one
     * and the GPU would draw the first view with the second view's parameters - the displaced water
     * and floating canopies that forced the direct path before. Clearing once per renderer per frame
     * instead makes every pass append its own parameter block at its own offset, which is exactly
     * what the recorded {@code vkCmdDrawIndexedIndirect} commands point at.
     */
    @Inject(
        method = "setupRenderer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/vulkanmod/vulkan/memory/buffer/IndirectBuffer;reset()V"
        ),
        cancellable = true
    )
    private void ip_keepIndirectParametersForTheWholeFrame(CallbackInfo ci) {
        if (VulkanTerrainBridge.indirectParametersAlreadyReset((Object) (Object) this)) {
            ci.cancel();
        }
    }
}
