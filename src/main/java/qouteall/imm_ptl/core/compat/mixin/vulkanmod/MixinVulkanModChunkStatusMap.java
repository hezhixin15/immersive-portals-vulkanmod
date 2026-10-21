// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.compat.mixin.vulkanmod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.vulkanmod_compatibility.VulkanChunkStatusRouter;

/**
 * Turns VulkanMod's single global chunk readiness table into one table per dimension.
 *
 * <p>The class itself is public with a public constructor and non final methods, so Immersive
 * Portals can install its own dispatcher into the public {@code INSTANCE} field and let the
 * {@code ChunkStatusMap} code do the actual work on a per dimension instance. These injections only
 * redirect the calls made on that dispatcher; every real table keeps running the original code on
 * its own storage, so nothing of VulkanMod's bookkeeping is reimplemented here.</p>
 */
@Pseudo
@Mixin(targets = "net.vulkanmod.render.chunk.ChunkStatusMap", remap = false)
public class MixinVulkanModChunkStatusMap {
    
    /**
     * VulkanMod wipes the table here whenever it binds its renderer to a world. With one table per
     * dimension there is nothing to wipe, and wiping would discard chunks the server will not send
     * again.
     */
    @Inject(method = "createInstance", at = @At("HEAD"), cancellable = true)
    private static void ip_installDispatcher(int renderDistance, CallbackInfo ci) {
        if (VulkanChunkStatusRouter.ensureDispatcher(renderDistance)) {
            ci.cancel();
        }
    }
    
    @Inject(method = "setChunkStatus", at = @At("HEAD"), cancellable = true)
    private void ip_routeChunkReady(int chunkX, int chunkZ, byte flag, CallbackInfo ci) {
        if (VulkanChunkStatusRouter.isDispatcher(this)) {
            ci.cancel();
            VulkanChunkStatusRouter.forwardSet(chunkX, chunkZ, flag);
        }
    }
    
    @Inject(method = "resetChunkStatus", at = @At("HEAD"), cancellable = true)
    private void ip_routeChunkNotReady(int chunkX, int chunkZ, byte flag, CallbackInfo ci) {
        if (VulkanChunkStatusRouter.isDispatcher(this)) {
            ci.cancel();
            VulkanChunkStatusRouter.forwardReset(chunkX, chunkZ, flag);
        }
    }
    
    @Inject(method = "chunkRenderReady", at = @At("HEAD"), cancellable = true)
    private void ip_routeReadinessQuery(
        int chunkX, int chunkZ, CallbackInfoReturnable<Boolean> cir
    ) {
        if (VulkanChunkStatusRouter.isDispatcher(this)) {
            cir.setReturnValue(VulkanChunkStatusRouter.forwardIsReady(chunkX, chunkZ));
        }
    }
}
