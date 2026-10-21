// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.compat.vulkanmod_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.RenderBackendCompat;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.Helper;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Gives every client world of Immersive Portals its own VulkanMod terrain renderer.
 *
 * <p>VulkanMod draws terrain through one globally shared {@code WorldRenderer}, reached only
 * through the private static field {@code INSTANCE}, and every vanilla renderer method it replaces
 * ({@code setupRender}, {@code renderSectionLayer}, {@code isSectionCompiled}, {@code setSectionDirty}
 * and friends) forwards to that field. Immersive Portals renders the world behind a portal by
 * swapping {@code Minecraft.levelRenderer} and calling into it, so with a single shared renderer the
 * portal would show the dimension the player is standing in, and the {@code setLevel} needed to
 * point the renderer at the other world rebuilds its whole section grid - which is what blanked the
 * screen for about a second.</p>
 *
 * <p>Because the field is not final and the constructor is merely private, each dimension can own a
 * renderer, and rendering a portal becomes a matter of binding the right one for the duration of the
 * offscreen pass and binding the player's back afterwards. Three pieces of state have to follow that
 * binding: the reference VulkanMod's own mixin adds to every level renderer, the chunk readiness
 * table (see {@link VulkanChunkStatusRouter}) and the queue of finished meshes, whose results are
 * validated against whichever renderer is bound when they are uploaded.</p>
 *
 * <p>All of this stays inert when the reflection of the current VulkanMod version fails, in which
 * case portals simply show nothing instead of corrupting the world the player is in.</p>
 */
public class VulkanTerrainBridge {
    
    /** One dimension's slice of VulkanMod's terrain state. */
    private static class Context {
        final ResourceKey<Level> dimension;
        final ClientLevel level;
        final Object worldRenderer;
        final Object taskDispatcher;
        /**
         * False for the renderer VulkanMod created itself, which it disposes of on its own and
         * which must not be freed while the window still shows it.
         */
        final boolean ownedByThisMod;
        /** How many dimensions share this renderer's meshing threads. */
        int builderThreads = -1;
        
        Context(
            ResourceKey<Level> dimension, ClientLevel level,
            Object worldRenderer, Object taskDispatcher, boolean ownedByThisMod
        ) {
            this.dimension = dimension;
            this.level = level;
            this.worldRenderer = worldRenderer;
            this.taskDispatcher = taskDispatcher;
            this.ownedByThisMod = ownedByThisMod;
        }
    }
    
    private static final Object2ObjectOpenHashMap<ResourceKey<Level>, Context> contexts =
        new Object2ObjectOpenHashMap<>();
    
    /** The renderer used for the image actually shown on screen. */
    private static Context foreground = null;
    
    /**
     * True while a dimension other than the on screen one is being drawn, which is when VulkanMod
     * must not pull the drawing back onto the main framebuffer. Read by
     * {@code MixinVulkanModDefaultMainPass}.
     */
    private static boolean renderingOffscreen = false;
    
    private static boolean warnedTooManyContexts = false;
    
    private static boolean missingContextReported = false;
    
    /** How many dimensions may hold a terrain renderer at once. */
    private static final int MAX_CONTEXTS = 4;
    
    /** Meshing threads a dimension that is only ever looked at through a portal gets. */
    private static final int BACKGROUND_BUILDER_THREADS = 1;
    
    private VulkanTerrainBridge() {}
    
    public static boolean isReady() {
        return RenderBackendCompat.IS_NON_GL_RENDERER && VulkanModAccess.isAvailable();
    }
    
    public static boolean isRenderingOffscreen() {
        return renderingOffscreen;
    }
    
    // ---- per frame -------------------------------------------------------
    
    /** Bumped once per frame, before any pass of that frame runs. */
    private static long frameSerial = 0;
    
    /**
     * The frame in which each terrain renderer last cleared its indirect draw parameters.
     *
     * <p>VulkanMod clears that buffer in {@code setupRenderer}, which it expects to run once per
     * frame. A portal pass is another full {@code renderLevel} in the same frame, so without this
     * every pass would start again at offset zero and overwrite the parameters the earlier passes
     * already recorded - the corruption that used to force terrain onto the direct path.</p>
     */
    private static final Map<Object, Long> indirectResetFrame = new IdentityHashMap<>();
    
    /** Whether the log has already shown a renderer serving more than one pass in a frame. */
    private static boolean indirectReuseReported = false;
    
    /** Called from {@code Renderer.preInitFrame}, before any terrain pass of the frame. */
    public static void beginFrame() {
        frameSerial++;
    }
    
    /**
     * Returns true when this renderer's indirect parameters were already cleared in this frame, in
     * which case the caller must skip the clear so that the passes append instead of overwrite.
     */
    public static boolean indirectParametersAlreadyReset(Object worldRenderer) {
        Long last = indirectResetFrame.get(worldRenderer);
        if (last != null && last.longValue() == frameSerial) {
            if (!indirectReuseReported) {
                indirectReuseReported = true;
                Helper.log(
                    "[IP-VK-INDIRECT] indirect draw parameters appended by a second pass in the same frame"
                );
            }
            return true;
        }
        indirectResetFrame.put(worldRenderer, frameSerial);
        return false;
    }
    
    /**
     * The player permanently entered another world, so that is what the screen has to show.
     * Immersive Portals' seamless teleport reaches this without {@code LevelRenderer.setLevel}, so
     * VulkanMod never learns about the change by itself.
     */
    public static void syncForeground() {
        if (!isReady()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || VulkanModAccess.getWorldRenderer() == null) {
            // no terrain renderer exists yet, so there is nothing to take over
            return;
        }
        Context current = getForeground();
        if (current != null && current.level == level) {
            return;
        }
        setForeground(level.dimension(), level);
    }
    
    public static void setForeground(ResourceKey<Level> dimension, ClientLevel level) {
        if (!isReady()) {
            return;
        }
        Context context = getContext(dimension, level);
        if (context == null) {
            return;
        }
        Context demoted = foreground;
        foreground = context;
        bind(context);
        // the world on screen is the one that needs the meshing pool the user asked for, while
        // everything else is only ever seen through a portal
        applyBuilderThreads(context, VulkanModAccess.getConfiguredBuilderThreads());
        if (demoted != null && demoted != context) {
            applyBuilderThreads(demoted, BACKGROUND_BUILDER_THREADS);
        }
    }
    
    /**
     * Uploads the meshes the builder threads produced since the last frame, for every dimension.
     *
     * <p>A result is validated against the renderer that is bound when it is uploaded and thrown away
     * when its chunk area does not belong to that renderer. VulkanMod drains one queue with one
     * dimension bound, which silently discarded the terrain of a world that is only ever looked at
     * through a portal - and because a meshing pool is chosen by whatever was bound when a mesh
     * *finished*, results also landed in another dimension's queue. Draining every queue with the
     * owner of each result bound fixes both.</p>
     *
     * <p>This is the only place a mesh may be uploaded from, and the {@code renderingOffscreen} guard
     * is what enforces it: it runs from {@code Renderer.preInitFrame}, before the
     * {@code UploadManager.submitUploads()} that carries this frame's bytes to the GPU.</p>
     */
    public static void drainSectionUploads() {
        if (!isReady() || renderingOffscreen) {
            return;
        }
        Context foregroundContext = getForeground();
        if (foregroundContext == null) {
            syncForeground();
            foregroundContext = getForeground();
            if (foregroundContext == null) {
                return;
            }
        }
        
        // this runs before the frame draws anything, so it is where the world on screen gets back
        // the vertex buffers that Minecraft currently uses for it
        VulkanModAccess.setWorldRendererRenderBuffers(
            foregroundContext.worldRenderer, Minecraft.getInstance().renderBuffers()
        );
        
        Object boundRenderer = VulkanModAccess.getWorldRenderer();
        ResourceKey<Level> boundDimension = VulkanChunkStatusRouter.getBoundDimension();
        
        try {
            for (Context context : contexts.values()) {
                drainQueue(context);
            }
        }
        finally {
            restoreBinding(boundRenderer, boundDimension);
        }
    }
    
    private static void drainQueue(Context context) {
        Queue<Object> queue = VulkanModAccess.getCompileResults(context.taskDispatcher);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        // only what is already there; new results may still be produced concurrently
        int count = queue.size();
        for (int i = 0; i < count; i++) {
            Object result = queue.poll();
            if (result == null) {
                break;
            }
            Context owner = findOwnerOf(result);
            if (owner == null) {
                // The dimension is gone, so nothing will ever draw this mesh. VulkanMod's own area
                // mismatch branch frees it the same way: UploadBuffer owns LWJGL allocations whose only
                // other release point is DrawBuffers.upload, which this result never reaches.
                VulkanModAccess.releaseCompileResult(result);
                continue;
            }
            bind(owner);
            VulkanModAccess.doSectionUpdate(owner.taskDispatcher, result);
            VulkanModAccess.setGraphNeedsUpdate(owner.worldRenderer, true);
        }
    }
    
    // ---- portal passes ---------------------------------------------------
    
    /**
     * Runs {@code rendering} with VulkanMod drawing the terrain of {@code dimension}.
     */
    public static void withDimensionRendering(
        ResourceKey<Level> dimension,
        ClientLevel level,
        Runnable rendering
    ) {
        if (!isReady()) {
            runOffscreenSuppressed(rendering);
            return;
        }
        
        // resolve what the screen uses before anything is bound, so that the renderer of the
        // dimension being drawn here can never be mistaken for the player's own
        Context foregroundContext = getForeground();
        Context context = getContext(dimension, level);
        if (foregroundContext == null || context == null) {
            reportMissingContext(dimension, foregroundContext, context);
            runOffscreenSuppressed(rendering);
            return;
        }
        
        boolean wasOffscreen = renderingOffscreen;
        // a portal can be visible inside a portal, so what has to be put back afterwards is whatever
        // was bound on entry rather than the world on screen
        Object previousRenderer = VulkanModAccess.getWorldRenderer();
        ResourceKey<Level> previousDimension = VulkanChunkStatusRouter.getBoundDimension();
        if (previousRenderer == null) {
            previousRenderer = foregroundContext.worldRenderer;
            previousDimension = foregroundContext.dimension;
        }
        Object previousBuffers = VulkanModAccess.getWorldRendererRenderBuffers(previousRenderer);
        renderingOffscreen = true;
        bind(context);
        // Immersive Portals swapped the entity vertex buffers out for the pass, and VulkanMod keeps
        // the ones it was constructed with, so block entities of the two worlds would share builders
        VulkanModAccess.setWorldRendererRenderBuffers(
            context.worldRenderer, Minecraft.getInstance().renderBuffers()
        );
        // No mesh may be uploaded from in here. Draw parameters sit in host memory and are read while
        // this frame's draw commands are recorded, but vertex and index bytes only reach the GPU at the
        // next UploadManager.submitUploads(), which runs once per frame inside Renderer.preInitFrame -
        // long before any portal pass. Uploading here would draw the frame with parameters for bytes
        // that are not there yet, which reads whatever the buffer held before. drainSectionUploads()
        // drains every dimension at that one safe point, so what is lost here is one frame of latency.
        try {
            rendering.run();
        }
        finally {
            renderingOffscreen = wasOffscreen;
            restorePassBinding(previousRenderer, previousDimension, previousBuffers);
        }
    }
    
    /**
     * Runs a portal pass with VulkanMod's pull-back onto the main framebuffer suppressed, even when
     * this bridge has no terrain context to bind for it. The pass is off screen either way: the
     * caller has already bound the portal's own framebuffer. Without the suppression,
     * {@code WorldRenderer.renderSectionLayer} rebinds the swapchain for every layer it draws, and
     * {@code Renderer.beginRenderPass} then rewrites viewport and scissor to the whole window, so the
     * terrain of that pass lands on the screen instead of inside the portal.
     */
    private static void runOffscreenSuppressed(Runnable rendering) {
        boolean wasOffscreen = renderingOffscreen;
        renderingOffscreen = true;
        try {
            rendering.run();
        }
        finally {
            renderingOffscreen = wasOffscreen;
        }
    }
    
    /**
     * The viewport VulkanMod recorded last while a portal pass ran, and how often it wrote one.
     * VulkanMod keeps the Y flip in the sign of the viewport height, so the sign says which of the
     * two conventions is currently in effect. Only integer fields are written per viewport; the text
     * is built once, when the layer below reports it.
     */
    private static int lastViewportX;
    private static int lastViewportY;
    private static int lastViewportWidth;
    private static int lastViewportHeight;
    private static boolean viewportRecorded = false;
    private static int viewportWrites = 0;
    
    /** Terrain layers whose portal pass viewport has already been reported as put back. */
    private static final Set<String> viewportRepairedLayers = new HashSet<>();
    
    /**
     * Puts the render target of a portal pass back into the state the pass started with, at the point
     * every terrain layer of that pass is entered.
     *
     * <p>{@code RenderTarget.bindWrite(true)} leaves the target with a GL viewport - it binds the
     * frame buffer, which VulkanMod does with its own inverted viewport, and then overwrites that with
     * {@code viewport(0, 0, viewWidth, viewHeight)}. That end state is what the opaque terrain of a
     * portal view is drawn with, and it is correct. VulkanMod's {@code VkGlFramebuffer.beginRendering}
     * stops one step earlier and always ends with the inverted viewport, while
     * {@code Renderer.beginRenderPass} short circuits for a frame buffer that is already bound, so
     * nothing corrects it afterwards. Any rebind of the portal's own target in the middle of the pass
     * therefore flips the image top to bottom for every draw that follows - the deferred clear of
     * {@code RenderTargetMixin.applyClear} is one, and it runs at the head of
     * {@code RenderTarget.getColorTextureId} and in {@code bindRead}. Translucent terrain is the layer
     * drawn last, so water is the only thing that ends up above the horizon while the lake bed under
     * it stays where it belongs.</p>
     *
     * <p>Rebinding through {@code bindWrite(true)} instead of setting the viewport by hand reproduces
     * the pass start state exactly, whichever way VulkanMod happens to flip it, and for a frame buffer
     * that is already bound it issues no second render pass.</p>
     */
    public static void keepTargetForLayer(Object renderType) {
        if (!renderingOffscreen || !PortalRendering.isRendering()) {
            return;
        }
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) {
            return;
        }
        target.bindWrite(true);
        if (renderType instanceof Enum<?> enumType) {
            String layerName = enumType.name();
            if (viewportRepairedLayers.add(layerName)) {
                Helper.log(String.format(
                    "[IP-VK-VIEWPORT] portal target rebound layer=%s target=%dx%d viewportBefore=%s "
                        + "viewportWrites=%d",
                    layerName, target.viewWidth, target.viewHeight, describeLastViewport(), viewportWrites
                ));
            }
        }
    }
    
    /**
     * Records a viewport VulkanMod is about to write, so {@link #keepTargetForLayer(Object)} can say
     * which one the layer it repairs would have been drawn with. Only counted inside a portal pass.
     */
    public static void noteViewportWrite(int x, int y, int width, int height) {
        if (!renderingOffscreen) {
            return;
        }
        viewportWrites++;
        lastViewportX = x;
        lastViewportY = y;
        lastViewportWidth = width;
        lastViewportHeight = height;
        viewportRecorded = true;
    }
    
    private static String describeLastViewport() {
        if (!viewportRecorded) {
            return "never";
        }
        return (lastViewportHeight < 0 ? "inverted" : "gl")
            + "(" + lastViewportX + "," + lastViewportY + ","
            + lastViewportWidth + "x" + lastViewportHeight + ")";
    }
    
    /**
     * Reports a portal pass that had to run without a terrain context. Logged once, because reaching
     * this branch means the water of that dimension is drawn by whatever renderer stayed bound, and
     * the alternative is to not draw it at all.
     */
    private static void reportMissingContext(
        ResourceKey<Level> dimension, Context foregroundContext, Context context
    ) {
        if (missingContextReported) {
            return;
        }
        missingContextReported = true;
        Helper.log(String.format(
            "[IP-VK-WATER] portal pass has no terrain context dim=%s foreground=%s context=%s",
            dimension,
            foregroundContext == null ? "null" : foregroundContext.dimension,
            context == null ? "null" : "present"
        ));
    }
    
    /**
     * Binds {@code worldRenderer} again, the way {@code withDimensionRendering} and
     * {@code withDimensionBound} promised before a pass started. Passing {@code null} for
     * {@code renderBuffers} leaves the vertex buffers alone, for a wrapper that never swapped them.
     */
    private static void restorePassBinding(
        Object worldRenderer, ResourceKey<Level> dimension, Object renderBuffers
    ) {
        if (worldRenderer == null) {
            return;
        }
        Context context = contextOf(worldRenderer);
        if (context != null) {
            bind(context);
        }
        else {
            restoreBinding(worldRenderer, dimension);
        }
        VulkanModAccess.setWorldRendererRenderBuffers(worldRenderer, renderBuffers);
    }
    
    // ---- world lifecycle -------------------------------------------------
    
    /**
     * Runs a terrain bookkeeping call such as {@code LevelRenderer.allChanged} with the terrain
     * state of {@code dimension} bound, so that it reaches the renderer of that world instead of
     * whoever happens to be drawn. Does nothing when no renderer exists for it yet, because a world
     * that has never been looked through a portal has no terrain state to rebuild.
     */
    public static void withDimensionBound(
        ResourceKey<Level> dimension, ClientLevel level, Runnable action
    ) {
        if (!isReady()) {
            action.run();
            return;
        }
        Context context = contexts.get(dimension);
        if (context == null || context.level != level) {
            return;
        }
        Context foregroundContext = getForeground();
        if (foregroundContext == null) {
            return;
        }
        boolean wasOffscreen = renderingOffscreen;
        Object previousRenderer = VulkanModAccess.getWorldRenderer();
        ResourceKey<Level> previousDimension = VulkanChunkStatusRouter.getBoundDimension();
        if (previousRenderer == null) {
            previousRenderer = foregroundContext.worldRenderer;
            previousDimension = foregroundContext.dimension;
        }
        // nothing may be drawn here, and the main framebuffer must stay untouched
        renderingOffscreen = true;
        bind(context);
        try {
            action.run();
        }
        finally {
            renderingOffscreen = wasOffscreen;
            restorePassBinding(previousRenderer, previousDimension, null);
        }
    }
    
    /**
     * Gives a newly created client world the terrain renderer that matches it. The level renderer was
     * constructed with whatever dimension was shared at that moment and would otherwise keep meshing
     * and drawing that one.
     */
    public static void onClientWorldCreated(
        ResourceKey<Level> dimension, ClientLevel level, LevelRenderer levelRenderer
    ) {
        if (!isReady()) {
            return;
        }
        Context context = getContext(dimension, level);
        if (context != null) {
            VulkanModAccess.setLevelRendererWorldRenderer(levelRenderer, context.worldRenderer);
            if (context != foreground) {
                applyBuilderThreads(context, BACKGROUND_BUILDER_THREADS);
            }
        }
    }
    
    /** A dimension's client world was thrown away, so its terrain state has to go with it. */
    public static void releaseContext(ResourceKey<Level> dimension) {
        if (!isReady()) {
            return;
        }
        Context context = contexts.remove(dimension);
        if (context == null) {
            return;
        }
        if (context == foreground) {
            foreground = null;
        }
        indirectResetFrame.remove(context.worldRenderer);
        VulkanChunkStatusRouter.invalidate(dimension);
        if (context.ownedByThisMod) {
            VulkanModAccess.disposeWorldRenderer(context.worldRenderer);
            if (VulkanModAccess.getWorldRenderer() == context.worldRenderer) {
                // must not leave a freed renderer bound
                Context next = getForeground();
                if (next != null) {
                    bind(next);
                }
            }
        }
    }
    
    /**
     * Forgets every terrain state Immersive Portals took over. The renderer that is on screen belongs
     * to VulkanMod and it disposes of that one itself, so it is only dropped from the bookkeeping.
     */
    public static void invalidateAll() {
        Object bound = VulkanModAccess.getWorldRenderer();
        for (Context context : contexts.values()) {
            // whatever is bound stays alive: VulkanMod hands the same instance to the next world
            // through WorldRenderer.init, and freeing it here would leave it bound after it is gone
            if (context.ownedByThisMod && context.worldRenderer != bound) {
                VulkanModAccess.disposeWorldRenderer(context.worldRenderer);
            }
            indirectResetFrame.remove(context.worldRenderer);
            VulkanChunkStatusRouter.invalidate(context.dimension);
        }
        contexts.clear();
        foreground = null;
    }
    
    /**
     * Puts the sections of {@code chunkPos} back onto the rebuild list of one dimension. VulkanMod
     * forgets a section forever when it visits it before its chunk became ready, so every state
     * change has to report it again - to the renderer of that dimension, not to whoever is bound.
     */
    public static void reDirtySections(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!isReady()) {
            return;
        }
        Context context = contexts.get(dimension);
        if (context == null) {
            context = getForeground();
        }
        if (context == null) {
            return;
        }
        Object sectionGrid = VulkanModAccess.getSectionGrid(context.worldRenderer);
        if (sectionGrid == null) {
            return;
        }
        for (Object section : VulkanModAccess.getRenderSectionsAt(sectionGrid, chunkX, chunkZ)) {
            if (section != null) {
                VulkanModAccess.setSectionDirty(section, false);
            }
        }
        VulkanModAccess.setGraphNeedsUpdate(context.worldRenderer, true);
    }
    
    // ---- internals -------------------------------------------------------
    
    private static void release(Context context) {
        if (foreground == context) {
            foreground = null;
        }
        if (context.ownedByThisMod &&
            context.worldRenderer != VulkanModAccess.getWorldRenderer()
        ) {
            VulkanModAccess.disposeWorldRenderer(context.worldRenderer);
        }
        indirectResetFrame.remove(context.worldRenderer);
        VulkanChunkStatusRouter.invalidate(context.dimension);
    }
    
    private static Context getContext(ResourceKey<Level> dimension, ClientLevel level) {
        Context existing = contexts.get(dimension);
        if (existing != null) {
            if (existing.level == level) {
                return existing;
            }
            // the client world object was replaced, so its terrain data is gone
            contexts.remove(dimension);
            release(existing);
        }
        
        if (contexts.size() >= MAX_CONTEXTS) {
            if (!warnedTooManyContexts) {
                warnedTooManyContexts = true;
                Helper.err(
                    "[Immersive Portals] too many VulkanMod terrain renderers, " +
                        "portals into further dimensions will stay empty"
                );
            }
            return null;
        }
        
        Context created = createContext(dimension, level);
        if (created != null) {
            contexts.put(dimension, created);
        }
        return created;
    }
    
    /**
     * Builds the terrain state of a world that VulkanMod has never seen. The new renderer has to be
     * bound before it is told which level to use, because {@code WorldRenderer.allChanged} reads the
     * shared instance to pick up the region cache for its section graph; done in the other order the
     * new world would mesh from the blocks of the old one.
     */
    private static Context createContext(ResourceKey<Level> dimension, ClientLevel level) {
        Object previousRenderer = VulkanModAccess.getWorldRenderer();
        ResourceKey<Level> previousDimension = VulkanChunkStatusRouter.getBoundDimension();
        
        try {
            Object worldRenderer = VulkanModAccess.constructWorldRenderer(
                Minecraft.getInstance().renderBuffers()
            );
            if (worldRenderer == null) {
                return null;
            }
            Object taskDispatcher = VulkanModAccess.getTaskDispatcher(worldRenderer);
            if (taskDispatcher == null) {
                VulkanModAccess.disposeWorldRenderer(worldRenderer);
                return null;
            }
            VulkanModAccess.setWorldRenderer(worldRenderer);
            VulkanChunkStatusRouter.setBoundDimension(dimension);
            VulkanModAccess.setWorldRendererLevel(worldRenderer, level);
            VulkanChunkStatusRouter.markLoadedChunksReady(level);
            return new Context(dimension, level, worldRenderer, taskDispatcher, true);
        }
        finally {
            restoreBinding(previousRenderer, previousDimension);
        }
    }
    
    private static void bind(Context context) {
        if (context == null) {
            return;
        }
        VulkanModAccess.setWorldRenderer(context.worldRenderer);
        VulkanChunkStatusRouter.setBoundDimension(context.dimension);
        VulkanModAccess.setChunkTaskDispatcher(context.taskDispatcher);
        healRegionCache(context.worldRenderer);
        patchLevelRenderers();
    }
    
    /**
     * Gives a renderer back the chunk cache its section graph has to mesh from.
     *
     * <p>{@code SectionGraph} takes its cache from whichever renderer is the shared one at the
     * moment it is built, which happens in {@code WorldRenderer.setLevel} and {@code allChanged} -
     * both of which VulkanMod also calls from its own mixins while a *different* dimension is bound.
     * A graph then reads block and light data out of another dimension's chunks while walking its own
     * section grid, and since the two dimensions have different build heights the section index runs
     * off either end of the chunk's section array.</p>
     */
    private static void healRegionCache(Object worldRenderer) {
        Object sectionGraph = VulkanModAccess.getSectionGraph(worldRenderer);
        if (sectionGraph == null) {
            return;
        }
        Object ownCache = VulkanModAccess.getRenderRegionCache(worldRenderer);
        if (ownCache != null &&
            VulkanModAccess.getSectionGraphRenderRegionCache(sectionGraph) != ownCache
        ) {
            VulkanModAccess.setSectionGraphRenderRegionCache(sectionGraph, ownCache);
        }
    }
    
    /**
     * Makes every piece of VulkanMod's terrain state that is only reachable through the shared
     * instance describe the renderer that is about to run a pass.
     *
     * <p>{@code RenderSection.createCompileTask} asks the shared instance which world to mesh from,
     * {@code SectionGraph} asks it for the render distance and the profiler, the readiness dispatcher
     * asks it which dimension is being drawn and {@code LevelRendererMixin} asks it for the camera
     * position, while the section grid being walked belongs to the renderer of the level renderer
     * executing the pass. Those two are the same object only as long as nothing rebinds in between,
     * and when they differ VulkanMod indexes a section of one dimension into another dimension's
     * chunk array, which crashes the frame. Re-asserting the binding at the point every terrain pass
     * enters makes the invariant hold however the pass was reached.</p>
     */
    public static void alignWithExecutingRenderer(Object executing) {
        if (!isReady() || executing == null) {
            return;
        }
        healRegionCache(executing);
        if (VulkanModAccess.getWorldRenderer() != executing) {
            VulkanModAccess.setWorldRenderer(executing);
            VulkanModAccess.setChunkTaskDispatcher(VulkanModAccess.getTaskDispatcher(executing));
        }
        ClientLevel level = VulkanModAccess.getWorldRendererLevel(executing);
        if (level != null && level.dimension() != VulkanChunkStatusRouter.getBoundDimension()) {
            VulkanChunkStatusRouter.setBoundDimension(level.dimension());
        }
    }
    
    /**
     * Puts back the renderer that was in use before a drain or a creation. Constructing a renderer
     * already redirects the static meshing queue at it, so the queue has to follow the instance even
     * when the restored renderer belongs to VulkanMod itself rather than to one of the contexts.
     */
    private static void restoreBinding(Object worldRenderer, ResourceKey<Level> dimension) {
        VulkanModAccess.setWorldRenderer(worldRenderer);
        if (worldRenderer != null) {
            VulkanModAccess.setChunkTaskDispatcher(
                VulkanModAccess.getTaskDispatcher(worldRenderer)
            );
        }
        VulkanChunkStatusRouter.setBoundDimension(dimension);
        if (contextOf(worldRenderer) != null) {
            patchLevelRenderers();
        }
    }
    
    /**
     * VulkanMod's level renderer mixin dispatches all terrain work through a reference each renderer
     * got at construction time, so the static instance alone does not say which dimension is drawn.
     */
    private static void patchLevelRenderers() {
        for (Map.Entry<ResourceKey<Level>, LevelRenderer> entry :
            ClientWorldLoader.WORLD_RENDERER_MAP.entrySet()) {
            Context context = contexts.get(entry.getKey());
            if (context != null) {
                VulkanModAccess.setLevelRendererWorldRenderer(entry.getValue(), context.worldRenderer);
            }
        }
    }
    
    /** The context owning {@code worldRenderer}, or null when VulkanMod still owns it. */
    private static Context contextOf(Object worldRenderer) {
        if (worldRenderer == null) {
            return null;
        }
        for (Context context : contexts.values()) {
            if (context.worldRenderer == worldRenderer) {
                return context;
            }
        }
        return null;
    }
    
    /**
     * The renderer of the world on screen. The very first time that is the one VulkanMod built
     * itself, which is adopted so that the world being shown is not disturbed; adopting it is only
     * allowed while it really is the world on screen, because two dimensions sharing one renderer
     * makes VulkanMod mesh one world's sections into the other's chunks.
     */
    private static Context getForeground() {
        if (foreground != null) {
            return foreground;
        }
        if (renderingOffscreen) {
            // something else is bound right now, adopting it would make that the screen's world
            return null;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        Context existing = contexts.get(level.dimension());
        if (existing != null) {
            if (existing.level == level) {
                foreground = existing;
                return existing;
            }
            release(existing);
            contexts.remove(level.dimension());
        }
        Object worldRenderer = VulkanModAccess.getWorldRenderer();
        if (worldRenderer == null) {
            return null;
        }
        Object taskDispatcher = VulkanModAccess.getTaskDispatcher(worldRenderer);
        if (taskDispatcher == null) {
            return null;
        }
        if (contextOf(worldRenderer) == null &&
            VulkanModAccess.getWorldRendererLevel(worldRenderer) == level
        ) {
            Context context =
                new Context(level.dimension(), level, worldRenderer, taskDispatcher, false);
            contexts.put(level.dimension(), context);
            foreground = context;
            Helper.log(
                "[Immersive Portals] adopted VulkanMod terrain renderer of " +
                    level.dimension().location()
            );
            patchLevelRenderers();
            return context;
        }
        // the shared renderer belongs to a world that is not on screen, so the screen needs its own
        Context created = getContext(level.dimension(), level);
        foreground = created;
        return created;
    }
    
    /** The dimension a finished mesh belongs to, judged by the chunk area it was written into. */
    private static Context findOwnerOf(Object compileResult) {
        Object chunkArea = VulkanModAccess.getCompileResultChunkArea(compileResult);
        int index = VulkanModAccess.getChunkAreaIndex(chunkArea);
        if (chunkArea == null || index < 0) {
            return null;
        }
        for (Context context : contexts.values()) {
            if (VulkanModAccess.getOwnedChunkArea(context.worldRenderer, index) == chunkArea) {
                return context;
            }
        }
        return null;
    }
    
    /**
     * {@code 0} makes VulkanMod pick the number of threads from the machine, which is what its own
     * settings screen means by "Auto" and what every {@code allChanged} would set anyway.
     */
    private static void applyBuilderThreads(Context context, int threadCount) {
        if (context.builderThreads == threadCount) {
            return;
        }
        context.builderThreads = threadCount;
        VulkanModAccess.createBuilderThreads(context.taskDispatcher, threadCount);
    }
}
