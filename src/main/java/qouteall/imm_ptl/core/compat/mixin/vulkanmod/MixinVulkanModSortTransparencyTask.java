// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.compat.mixin.vulkanmod;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.vulkanmod_compatibility.VulkanModAccess;

/**
 * Sorts a section's quads against the camera of the world that section belongs to.
 *
 * <p>{@code SortTransparencyTask} is constructed with the camera its caller was rendering, keeps it in
 * {@code ChunkTask.cameraPos}, and then ignores it: {@code runTask} reads
 * {@code WorldRenderer.getCameraPos()}, which is the camera of whichever renderer the render thread
 * last made current. Immersive Portals gives every visible dimension its own renderer, so the two are
 * different objects, and this task runs on a meshing thread that keeps going while the render thread
 * has moved on to a portal view of another dimension. The section then gets sorted against a camera
 * hundreds of blocks away in a different world.</p>
 *
 * <p>That matters because this task is the only path that re-uploads a single terrain layer from
 * outside the normal meshing pass, and the layer it re-uploads is always translucent - so it is the
 * one layer whose drawn contents can be built from another world's point of view while every matrix,
 * camera and buffer the render thread uses stays correct.</p>
 *
 * <p>{@code cameraPos} is read through {@link VulkanModAccess} rather than {@code @Shadow} because the
 * field is declared in {@code ChunkTask} while the call that has to be replaced is in this subclass.
 * Shadowing it from here failed at runtime with {@code @Shadow field cameraPos was not located in the
 * target class SortTransparencyTask}, Mixin then aborted the whole transformation of that class, and
 * the game died as soon as a level was loaded. Reading the field reflectively leaves this mixin with
 * only two dependencies on {@code SortTransparencyTask} itself - the {@code runTask} method and the
 * {@code WorldRenderer.getCameraPos()} call, both verified against the shipped class file.</p>
 */
@Pseudo
@Mixin(targets = "net.vulkanmod.render.chunk.build.task.SortTransparencyTask", remap = false)
public abstract class MixinVulkanModSortTransparencyTask {
    
    @WrapOperation(
        method = "runTask",
        at = @At(
            value = "INVOKE",
            target = "Lnet/vulkanmod/render/chunk/WorldRenderer;getCameraPos()Lorg/joml/Vector3d;"
        )
    )
    private Vector3d ip_sortAgainstThisTasksOwnCamera(Operation<Vector3d> original) {
        Vector3d currentRendererCamera = original.call();
        Vector3d scheduledCamera = VulkanModAccess.getChunkTaskCameraPos(this);
        if (scheduledCamera == null) {
            return currentRendererCamera;
        }
        return scheduledCamera;
    }
}
