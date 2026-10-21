// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.compat.mixin.vulkanmod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.vulkanmod_compatibility.VulkanChunkStatusRouter;

/**
 * Records which world light data is arriving for, before VulkanMod marks the chunk ready.
 *
 * <p>VulkanMod reports light readiness from the single shared packet listener and has no way of
 * knowing which dimension a packet belongs to, because it only ever runs one world at a time.
 * Immersive Portals switches the listener's level while it applies the state of a world behind a
 * portal, so reading it here is enough to route the write to that world's readiness table.</p>
 *
 * <p>Block data needs no counterpart: Immersive Portals replaces the client chunk cache completely
 * (see {@code ImmPtlClientChunkMap}), so VulkanMod's own injection never runs for it.</p>
 */
@Mixin(ClientPacketListener.class)
public class MixinVulkanModClientPacketListener {
    
    @Shadow private ClientLevel level;
    
    @Inject(method = "applyLightData", at = @At("HEAD"))
    private void ip_markLightDimension(
        int x, int z, ClientboundLightUpdatePacketData lightData, CallbackInfo ci
    ) {
        VulkanChunkStatusRouter.setWriteDimension(level.dimension());
    }
    
    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"))
    private void ip_markChunkForgetDimension(
        ClientboundForgetLevelChunkPacket packet, CallbackInfo ci
    ) {
        VulkanChunkStatusRouter.setWriteDimension(level.dimension());
    }
}
