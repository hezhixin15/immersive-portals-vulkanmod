// Modified for the unofficial VulkanMod fork of Immersive Portals (see NOTICE).
package qouteall.imm_ptl.core.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import qouteall.imm_ptl.core.compat.vulkanmod_compatibility.VulkanChunkStatusRouter;
import qouteall.q_misc_util.Helper;

import java.nio.FloatBuffer;

/**
 * Abstraction over the graphics backend that Minecraft is currently running on.
 *
 * <p>Immersive Portals originally assumed that a real OpenGL context is always available, so it
 * calls the LWJGL OpenGL entry points directly in a lot of places. When a mod such as VulkanMod
 * replaces the renderer, no OpenGL context is created at all, so every raw LWJGL call that is not
 * intercepted by that mod throws
 * {@code java.lang.IllegalStateException: No GLCapabilities instance set for the current thread}.</p>
 *
 * <p>This class reports which OpenGL features can actually be used, so the rest of the mod can
 * degrade gracefully instead of crashing. It also makes sure that the mod never switches itself
 * into a "disabled / dummy" mode just because a non-OpenGL renderer was detected.</p>
 */
public class RenderBackendCompat {
    
    public static final String VULKAN_MOD_ID = "vulkanmod";
    
    /**
     * VulkanMod (and any other mod that removes the OpenGL context) is loaded.
     */
    public static final boolean IS_NON_GL_RENDERER =
        FabricLoader.getInstance().isModLoaded(VULKAN_MOD_ID);
    
    /**
     * True when the raw LWJGL {@code org.lwjgl.opengl.GL*} functions cannot be called because
     * there is no OpenGL context on the current thread.
     */
    public static final boolean IS_RAW_GL_UNAVAILABLE = IS_NON_GL_RENDERER;
    
    private RenderBackendCompat() {}
    
    // ---- feature queries -------------------------------------------------
    
    /** Stencil buffer based portal masking. */
    public static boolean supportsStencil() {
        return !IS_RAW_GL_UNAVAILABLE;
    }
    
    /** GL15 / GL33 occlusion queries. */
    public static boolean supportsOcclusionQuery() {
        return !IS_RAW_GL_UNAVAILABLE;
    }
    
    /** {@code GL32.GL_DEPTH_CLAMP}. */
    public static boolean supportsDepthClamp() {
        return !IS_RAW_GL_UNAVAILABLE;
    }
    
    /** Fixed-function {@code GL_CLIP_PLANE0}. The shader-uniform based clipping still works. */
    public static boolean supportsFixedFunctionClipPlane() {
        return !IS_RAW_GL_UNAVAILABLE;
    }
    
    /** {@code GL43.glCopyImageSubData}. */
    public static boolean supportsCopyImageSubData() {
        return !IS_RAW_GL_UNAVAILABLE;
    }
    
    /** {@code GL11.glReadPixels}. */
    public static boolean supportsReadPixels() {
        return !IS_RAW_GL_UNAVAILABLE;
    }
    
    /** {@code GL11.glCullFace}. VulkanMod routes culling through RenderSystem instead. */
    public static boolean supportsRawCullFace() {
        return !IS_RAW_GL_UNAVAILABLE;
    }
    
    /** Raw {@code glUniform*} / {@code glGetUniformLocation} on arbitrary program ids. */
    public static boolean supportsRawUniform() {
        return !IS_RAW_GL_UNAVAILABLE;
    }
    
    /** Caching of GL buffer / VAO ids ({@code IPGlobal.cacheGlBuffer}). */
    public static boolean supportsGlBufferCaching() {
        return !IS_RAW_GL_UNAVAILABLE;
    }
    
    /**
     * Whether the mod should force the framebuffer based portal renderer
     * (the "compatibility" renderer) instead of the stencil based one.
     */
    public static boolean shouldForceFramebufferPortalRenderer() {
        return IS_NON_GL_RENDERER;
    }
    
    /**
     * Never let a non-OpenGL renderer push the mod into a degraded "nothing is rendered" mode.
     * This is the place where the old "auto disable a lot of features" behaviour would live.
     */
    public static boolean shouldDisablePortalRendering() {
        return false;
    }
    
    public static String getBackendName() {
        return IS_NON_GL_RENDERER ? "Vulkan (VulkanMod)" : "OpenGL";
    }
    
    // ---- clear colour -----------------------------------------------------
    
    private static java.lang.reflect.Field vulkanClearColorField;
    
    private static boolean vulkanClearColorUnavailable = false;
    
    /**
     * The clear colour the backend holds right now, or {@code null} when it cannot be read.
     *
     * <p>VulkanMod stores this in one process-wide buffer, and its swapchain render pass uses that
     * same buffer as the frame-begin clear value, so a caller that sets it to something loud has to
     * put it back or the next frame starts in that colour. Minecraft does not expose a getter, and
     * VulkanMod does not implement {@code glGetFloatv}, so the two backends are read differently.</p>
     */
    public static float[] getClearColor() {
        if (IS_NON_GL_RENDERER) {
            return getVulkanClearColor();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(4);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, buffer);
            return new float[] {buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3)};
        }
        catch (Throwable throwable) {
            return null;
        }
    }
    
    private static float[] getVulkanClearColor() {
        try {
            if (vulkanClearColorField == null) {
                if (vulkanClearColorUnavailable) {
                    return null;
                }
                Class<?> type = Class.forName("net.vulkanmod.vulkan.VRenderSystem");
                vulkanClearColorField = type.getField("clearColor");
            }
            FloatBuffer buffer = (FloatBuffer) vulkanClearColorField.get(null);
            // absolute reads, so the position VulkanMod keeps for its own copying is left alone
            return new float[] {buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3)};
        }
        catch (Throwable throwable) {
            vulkanClearColorUnavailable = true;
            return null;
        }
    }
    
    // ---- chunk status bridging -------------------------------------------
    
    /**
     * VulkanMod marks a chunk as "block data ready" by injecting into the vanilla
     * {@code ClientChunkCache.replaceWithPacketData}. Immersive Portals overrides that method
     * (see {@code ImmPtlClientChunkMap}) so the injection never runs, the chunk never reaches
     * the "ready" state, no compile task is ever created for it and the whole terrain stays
     * invisible. Bridge the notification manually here, for the world the chunk actually belongs
     * to - the readiness tables are per dimension, see {@link VulkanChunkStatusRouter}.
     */
    public static void notifyVulkanChunkDataReady(
        ResourceKey<Level> dimension, int chunkX, int chunkZ
    ) {
        notifyVulkanChunkStatus(dimension, chunkX, chunkZ, VulkanChunkStatusRouter.FLAG_DATA_READY);
    }
    
    /** Counterpart of {@link #notifyVulkanChunkDataReady} for chunk unloading. */
    public static void notifyVulkanChunkUnloaded(
        ResourceKey<Level> dimension, int chunkX, int chunkZ
    ) {
        if (!IS_NON_GL_RENDERER) {
            return;
        }
        VulkanChunkStatusRouter.withDimension(dimension, () ->
            VulkanChunkStatusRouter.forwardReset(chunkX, chunkZ, VulkanChunkStatusRouter.FLAG_DATA_READY));
    }
    
    private static void notifyVulkanChunkStatus(
        ResourceKey<Level> dimension, int chunkX, int chunkZ, byte flag
    ) {
        if (!IS_NON_GL_RENDERER) {
            return;
        }
        VulkanChunkStatusRouter.withDimension(dimension, () ->
            VulkanChunkStatusRouter.forwardSet(chunkX, chunkZ, flag));
    }
    
    // ---- world renderer level binding ------------------------------------
    
    private static boolean vulkanLevelBindingSuppressed = false;
    
    /**
     * True while {@link #withVulkanLevelBindingSuppressed} is running. Read by
     * {@code MixinVulkanModWorldRenderer}.
     */
    public static boolean isVulkanLevelBindingSuppressed() {
        return vulkanLevelBindingSuppressed;
    }
    
    /**
     * Runs {@code action}, which is expected to call {@code LevelRenderer.setLevel}, without
     * letting the terrain renderer it hands to VulkanMod follow along.
     *
     * <p>Immersive Portals keeps one {@code LevelRenderer} per dimension. Vanilla
     * {@code setLevel} is the only thing that gives a renderer its {@code level},
     * {@code SectionRenderDispatcher} and {@code ViewArea}, so skipping it leaves all three null
     * and the renderer throws the moment anything touches it. Letting it run untouched rebinds the
     * terrain renderer that the dimension owns to a level it has already been given and rebuilds its
     * whole section grid, blanking the screen for about a second. Suppressing only the VulkanMod
     * half of it gets both.</p>
     */
    public static void withVulkanLevelBindingSuppressed(Runnable action) {
        if (!IS_NON_GL_RENDERER) {
            action.run();
            return;
        }
        
        boolean wasSuppressed = vulkanLevelBindingSuppressed;
        vulkanLevelBindingSuppressed = true;
        try {
            action.run();
        }
        finally {
            vulkanLevelBindingSuppressed = wasSuppressed;
        }
    }
}
