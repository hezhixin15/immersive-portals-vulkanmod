// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.compat.vulkanmod_compatibility;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.compat.RenderBackendCompat;
import qouteall.q_misc_util.Helper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;

/**
 * Reflection access to the internals of VulkanMod that Immersive Portals has to drive.
 *
 * <p>VulkanMod is not a compile time dependency of this mod, so nothing here may reference its
 * classes in source. Every lookup is resolved lazily and every failure is reported once and then
 * degraded, because a renderer that cannot be driven only means that portal content stays empty
 * - it must never take the game down with it.</p>
 *
 * <p>Class names of Minecraft itself differ between the development and the shipped mapping, so
 * those lookups take the intermediary name first and fall back to the named one.</p>
 */
public class VulkanModAccess {
    
    public static final String WORLD_RENDERER_CLASS_NAME = "net.vulkanmod.render.chunk.WorldRenderer";
    public static final String CHUNK_STATUS_MAP_CLASS_NAME = "net.vulkanmod.render.chunk.ChunkStatusMap";
    
    private static boolean resolved = false;
    private static boolean available = false;
    
    private static Class<?> worldRendererClass;
    private static Field worldRendererInstanceField;
    private static Constructor<?> worldRendererConstructor;
    private static Method worldRendererSetLevel;
    private static Method worldRendererUploadSections;
    private static Method worldRendererCleanUp;
    private static Field worldRendererTaskDispatcherField;
    private static Field worldRendererRenderBuffersField;
    private static Field worldRendererLevelField;
    private static Field worldRendererSectionGraphField;
    private static Field worldRendererRenderRegionCacheField;
    private static Method worldRendererGetChunkAreaManager;
    private static Method chunkAreaManagerGetArea;
    
    private static Field chunkTaskDispatcherField;
    
    /** {@code ChunkTask.cameraPos}, the camera a meshing task was scheduled with. */
    private static Field chunkTaskCameraPosField;
    
    private static Field taskDispatcherCompileResultsField;
    private static Method taskDispatcherDoSectionUpdate;
    private static Method taskDispatcherCreateThreads;
    private static Field compileResultSectionField;
    private static Field compileResultRenderedLayersField;
    private static Method uploadBufferRelease;
    private static Method sectionGetChunkArea;
    private static Field chunkAreaIndexField;

    /** {@code Initializer.CONFIG.builderThreads}, so that a context can keep the user's setting. */
    private static Field initializerConfigField;
    private static Field configBuilderThreadsField;
    
    private static Field chunkStatusMapInstanceField;
    private static Constructor<?> chunkStatusMapConstructor;
    private static Method chunkStatusMapSet;
    private static Method chunkStatusMapReset;
    private static Method chunkStatusMapReady;
    
    private static Field sectionGridField;
    private static Field graphNeedsUpdateField;
    private static Field sectionGraphRenderRegionCacheField;
    private static Method renderSectionsAtMethod;
    private static Method sectionSetDirty;
    
    /** The field VulkanMod's own mixin adds to every vanilla level renderer. */
    private static Field levelRendererWorldRendererField;
    
    private VulkanModAccess() {}
    
    /**
     * False when this is not the expected VulkanMod version, in which case the whole cross
     * dimension terrain feature switches itself off instead of guessing.
     */
    public static boolean isAvailable() {
        resolve();
        return available;
    }
    
    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!RenderBackendCompat.IS_NON_GL_RENDERER) {
            return;
        }
        try {
            resolveWorldRenderer();
            resolveTaskDispatcher();
            resolveChunkStatusMap();
            resolveSectionApi();
            resolveLevelRendererField();
            available = true;
            // The trailing clause is the build marker: a log without it is running an older jar, which
            // matters because "no portal upload groups" and "old jar" would otherwise look identical.
            Helper.log(
                "[Immersive Portals] VulkanMod terrain bridge ready, mid-frame mesh uploads refused,"
                    + " indirect draw kept across portal passes, portal image cache built in,"
                    + " fully covered portals skipped"
            );
        }
        catch (Throwable e) {
            available = false;
            Helper.err("[Immersive Portals] cannot drive VulkanMod terrain renderer: " + e);
        }
    }
    
    private static Class<?> minecraftClass(String intermediaryName, Class<?> namedClass) {
        try {
            return Class.forName(intermediaryName);
        }
        catch (Throwable e) {
            return namedClass;
        }
    }
    
    private static void resolveWorldRenderer() throws Exception {
        worldRendererClass = Class.forName(WORLD_RENDERER_CLASS_NAME);
        
        worldRendererInstanceField = worldRendererClass.getDeclaredField("INSTANCE");
        worldRendererInstanceField.setAccessible(true);
        
        worldRendererConstructor = worldRendererClass.getDeclaredConstructor(
            minecraftClass("net.minecraft.class_4599", RenderBuffers.class)
        );
        worldRendererConstructor.setAccessible(true);
        
        worldRendererSetLevel = worldRendererClass.getDeclaredMethod(
            "setLevel", minecraftClass("net.minecraft.class_638", ClientLevel.class)
        );
        worldRendererUploadSections = worldRendererClass.getDeclaredMethod("uploadSections");
        worldRendererCleanUp = worldRendererClass.getDeclaredMethod("cleanUp");
        worldRendererGetChunkAreaManager =
            worldRendererClass.getDeclaredMethod("getChunkAreaManager");
        
        worldRendererTaskDispatcherField = worldRendererClass.getDeclaredField("taskDispatcher");
        worldRendererTaskDispatcherField.setAccessible(true);
        worldRendererRenderBuffersField = worldRendererClass.getDeclaredField("renderBuffers");
        worldRendererRenderBuffersField.setAccessible(true);
        worldRendererLevelField = worldRendererClass.getDeclaredField("level");
        worldRendererLevelField.setAccessible(true);
        worldRendererSectionGraphField = worldRendererClass.getDeclaredField("sectionGraph");
        worldRendererSectionGraphField.setAccessible(true);
        worldRendererRenderRegionCacheField =
            worldRendererClass.getDeclaredField("renderRegionCache");
        worldRendererRenderRegionCacheField.setAccessible(true);
        
        Class<?> chunkAreaManagerClass =
            Class.forName("net.vulkanmod.render.chunk.ChunkAreaManager");
        chunkAreaManagerGetArea =
            chunkAreaManagerClass.getDeclaredMethod("getChunkArea", int.class);
    }
    
    /**
     * The camera a task was scheduled for, read by the translucency resort so that it does not use
     * whichever renderer the render thread last made current.
     *
     * <p>Deliberately resolved separately from the rest: a VulkanMod build without this field must
     * keep sorting the way it always did, not lose the whole cross dimension bridge.</p>
     */
    private static void resolveChunkTaskCameraField(Class<?> chunkTaskClass) {
        try {
            chunkTaskCameraPosField = chunkTaskClass.getDeclaredField("cameraPos");
            chunkTaskCameraPosField.setAccessible(true);
        }
        catch (Throwable e) {
            chunkTaskCameraPosField = null;
        }
    }
    
    private static void resolveTaskDispatcher() throws Exception {
        Class<?> chunkTaskClass =
            Class.forName("net.vulkanmod.render.chunk.build.task.ChunkTask");
        chunkTaskDispatcherField = chunkTaskClass.getDeclaredField("taskDispatcher");
        chunkTaskDispatcherField.setAccessible(true);
        resolveChunkTaskCameraField(chunkTaskClass);
        
        Class<?> taskDispatcherClass =
            Class.forName("net.vulkanmod.render.chunk.build.task.TaskDispatcher");
        taskDispatcherCompileResultsField = taskDispatcherClass.getDeclaredField("compileResults");
        taskDispatcherCompileResultsField.setAccessible(true);
        taskDispatcherDoSectionUpdate = taskDispatcherClass.getDeclaredMethod(
            "doSectionUpdate",
            Class.forName("net.vulkanmod.render.chunk.build.task.CompileResult")
        );
        taskDispatcherDoSectionUpdate.setAccessible(true);
        taskDispatcherCreateThreads = taskDispatcherClass.getDeclaredMethod(
            "createThreads", int.class
        );

        Class<?> compileResultClass =
            Class.forName("net.vulkanmod.render.chunk.build.task.CompileResult");
        compileResultSectionField = compileResultClass.getDeclaredField("renderSection");
        compileResultSectionField.setAccessible(true);
        compileResultRenderedLayersField = compileResultClass.getDeclaredField("renderedLayers");
        compileResultRenderedLayersField.setAccessible(true);
        uploadBufferRelease = Class.forName("net.vulkanmod.render.chunk.build.UploadBuffer")
            .getDeclaredMethod("release");

        resolveBuilderThreadConfig();
    }

    /**
     * Reading the user's own meshing thread setting is optional: a missing field only means that
     * every context falls back to letting VulkanMod pick the count from the machine.
     */
    private static void resolveBuilderThreadConfig() {
        try {
            Class<?> initializerClass = Class.forName("net.vulkanmod.Initializer");
            initializerConfigField = initializerClass.getDeclaredField("CONFIG");
            initializerConfigField.setAccessible(true);
            configBuilderThreadsField =
                initializerConfigField.getType().getDeclaredField("builderThreads");
        }
        catch (Throwable e) {
            initializerConfigField = null;
            configBuilderThreadsField = null;
        }
    }
    
    private static void resolveSectionApi() throws Exception {
        Class<?> renderSectionClass = Class.forName("net.vulkanmod.render.chunk.RenderSection");
        sectionGetChunkArea = renderSectionClass.getDeclaredMethod("getChunkArea");
        chunkAreaIndexField =
            Class.forName("net.vulkanmod.render.chunk.ChunkArea").getDeclaredField("index");
        
        sectionGridField = worldRendererClass.getDeclaredField("sectionGrid");
        sectionGridField.setAccessible(true);
        graphNeedsUpdateField = worldRendererClass.getDeclaredField("graphNeedsUpdate");
        graphNeedsUpdateField.setAccessible(true);

        Class<?> sectionGraphClass =
            Class.forName("net.vulkanmod.render.chunk.graph.SectionGraph");
        sectionGraphRenderRegionCacheField =
            sectionGraphClass.getDeclaredField("renderRegionCache");
        sectionGraphRenderRegionCacheField.setAccessible(true);
        
        Class<?> sectionGridClass = Class.forName("net.vulkanmod.render.chunk.SectionGrid");
        renderSectionsAtMethod =
            sectionGridClass.getDeclaredMethod("getRenderSectionsAt", int.class, int.class);
        
        sectionSetDirty = renderSectionClass.getDeclaredMethod("setDirty", boolean.class);
    }
    
    private static void resolveChunkStatusMap() throws Exception {
        Class<?> chunkStatusMapClass = Class.forName(CHUNK_STATUS_MAP_CLASS_NAME);
        
        chunkStatusMapInstanceField = chunkStatusMapClass.getDeclaredField("INSTANCE");
        chunkStatusMapInstanceField.setAccessible(true);
        
        chunkStatusMapConstructor = chunkStatusMapClass.getDeclaredConstructor(int.class);
        chunkStatusMapConstructor.setAccessible(true);
        chunkStatusMapSet =
            chunkStatusMapClass.getDeclaredMethod("setChunkStatus", int.class, int.class, byte.class);
        chunkStatusMapReset =
            chunkStatusMapClass.getDeclaredMethod("resetChunkStatus", int.class, int.class, byte.class);
        chunkStatusMapReady =
            chunkStatusMapClass.getDeclaredMethod("chunkRenderReady", int.class, int.class);
    }
    
    /**
     * VulkanMod's {@code LevelRendererMixin} gives every level renderer its own reference to the
     * shared terrain renderer and dispatches all terrain work through it, so pointing the static
     * instance elsewhere is not enough - the reference has to follow.
     */
    private static void resolveLevelRendererField() {
        try {
            levelRendererWorldRendererField =
                LevelRenderer.class.getDeclaredField("worldRenderer");
            levelRendererWorldRendererField.setAccessible(true);
        }
        catch (Throwable e) {
            levelRendererWorldRendererField = null;
        }
    }
    
    // ---- world renderer --------------------------------------------------
    
    /** The renderer VulkanMod currently draws terrain with. */
    public static Object getWorldRenderer() {
        if (!isAvailable()) {
            return null;
        }
        try {
            return worldRendererInstanceField.get(null);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    public static void setWorldRenderer(Object worldRenderer) {
        if (!isAvailable()) {
            return;
        }
        try {
            worldRendererInstanceField.set(null, worldRenderer);
        }
        catch (Throwable e) {
            reportOnce("set world renderer", e);
        }
    }
    
    /**
     * Creates a terrain renderer for another world. VulkanMod only ever builds one, through
     * {@code WorldRenderer.init}, which hands out the existing instance, so the private
     * constructor has to be driven directly.
     *
     * <p>The level is not set here: the caller has to install the new renderer as the shared one
     * first, because {@code setLevel} reads the shared instance while building the section graph.</p>
     */
    public static Object constructWorldRenderer(RenderBuffers renderBuffers) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return worldRendererConstructor.newInstance(renderBuffers);
        }
        catch (Throwable e) {
            reportOnce("create world renderer", e);
            return null;
        }
    }
    
    public static void setWorldRendererLevel(Object worldRenderer, ClientLevel level) {
        if (!isAvailable()) {
            return;
        }
        try {
            worldRendererSetLevel.invoke(worldRenderer, level);
        }
        catch (Throwable e) {
            reportOnce("set renderer level", e);
        }
    }
    
    /** Releases the GPU buffers and builder threads of a renderer that is being thrown away. */
    public static void disposeWorldRenderer(Object worldRenderer) {
        if (!isAvailable()) {
            return;
        }
        try {
            // setLevel(null) frees the section grid; the builder threads belong to the shared
            // dispatcher and keep running for the remaining dimensions
            worldRendererSetLevel.invoke(worldRenderer, new Object[]{null});
            worldRendererCleanUp.invoke(worldRenderer);
        }
        catch (Throwable e) {
            reportOnce("dispose world renderer", e);
        }
    }
    
    /**
     * The world a renderer meshes and draws. VulkanMod itself reads the same value from the shared
     * instance when it builds a mesh, so this is what tells which dimension a renderer owns.
     */
    public static ClientLevel getWorldRendererLevel(Object worldRenderer) {
        if (!isAvailable() || worldRenderer == null) {
            return null;
        }
        try {
            Object level = worldRendererLevelField.get(worldRenderer);
            return level instanceof ClientLevel ? (ClientLevel) level : null;
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * The chunk cache a renderer meshes from. A section graph picks its cache up from whatever
     * renderer is shared when the graph is built, so the two can disagree.
     */
    public static Object getRenderRegionCache(Object worldRenderer) {
        if (!isAvailable() || worldRenderer == null) {
            return null;
        }
        try {
            return worldRendererRenderRegionCacheField.get(worldRenderer);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    public static Object getSectionGraph(Object worldRenderer) {
        if (!isAvailable() || worldRenderer == null) {
            return null;
        }
        try {
            return worldRendererSectionGraphField.get(worldRenderer);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    public static Object getSectionGraphRenderRegionCache(Object sectionGraph) {
        if (!isAvailable() || sectionGraph == null) {
            return null;
        }
        try {
            return sectionGraphRenderRegionCacheField.get(sectionGraph);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    public static void setSectionGraphRenderRegionCache(Object sectionGraph, Object cache) {
        if (!isAvailable() || sectionGraph == null || cache == null) {
            return;
        }
        try {
            sectionGraphRenderRegionCacheField.set(sectionGraph, cache);
        }
        catch (Throwable e) {
            reportOnce("give a section graph its own chunk cache", e);
        }
    }
    
    // ---- task dispatcher -------------------------------------------------
    
    public static Object getTaskDispatcher(Object worldRenderer) {
        if (!isAvailable() || worldRenderer == null) {
            return null;
        }
        try {
            return worldRendererTaskDispatcherField.get(worldRenderer);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * Makes a renderer use the meshing thread pool of another one. Every context would otherwise
     * start its own pool with its own vertex buffers, which costs memory for each dimension that
     * is only ever looked at through a portal.
     */
    public static void setTaskDispatcher(Object worldRenderer, Object taskDispatcher) {
        if (!isAvailable() || worldRenderer == null) {
            return;
        }
        try {
            worldRendererTaskDispatcherField.set(worldRenderer, taskDispatcher);
        }
        catch (Throwable e) {
            reportOnce("share task dispatcher", e);
        }
    }
    
    /**
     * VulkanMod reads this static field from its builder threads at the moment a mesh finishes,
     * which decides whose upload queue the result lands in. Keeping it on one object is what makes
     * the results of every dimension arrive in a queue that is actually drained.
     */
    public static void setChunkTaskDispatcher(Object taskDispatcher) {
        if (!isAvailable() || taskDispatcher == null) {
            // clearing it would make the builder threads dereference null when a mesh finishes
            return;
        }
        try {
            chunkTaskDispatcherField.set(null, taskDispatcher);
        }
        catch (Throwable e) {
            reportOnce("set chunk task dispatcher", e);
        }
    }
    
    /** The camera {@code task} was created for, or null when this VulkanMod build has no such field. */
    public static Vector3d getChunkTaskCameraPos(Object task) {
        if (task == null || !isAvailable() || chunkTaskCameraPosField == null) {
            return null;
        }
        try {
            Object value = chunkTaskCameraPosField.get(task);
            return value instanceof Vector3d ? (Vector3d) value : null;
        }
        catch (Throwable e) {
            reportOnce("read the camera a meshing task carries", e);
            return null;
        }
    }
    
    /**
     * Resizes the meshing thread pool of one dimension. VulkanMod recreates the pool in
     * {@code WorldRenderer.allChanged}, so a world that is only ever looked at through a portal has
     * to be shrunk afterwards: each pool carries its own set of vertex builder buffers.
     * Passing {@code 0} lets VulkanMod derive the count from the machine, which is what it does for
     * the world on screen.
     */
    public static void createBuilderThreads(Object taskDispatcher, int threadCount) {
        if (!isAvailable() || taskDispatcher == null) {
            return;
        }
        try {
            taskDispatcherCreateThreads.invoke(taskDispatcher, Math.max(threadCount, 0));
        }
        catch (Throwable e) {
            reportOnce("create builder threads", e);
        }
    }
    
    /** The meshing thread count the user configured in VulkanMod's own options, or auto. */
    public static int getConfiguredBuilderThreads() {
        if (!isAvailable() || configBuilderThreadsField == null) {
            return 0;
        }
        try {
            Object config = initializerConfigField.get(null);
            if (config == null) {
                return 0;
            }
            return Math.max(configBuilderThreadsField.getInt(config), 0);
        }
        catch (Throwable e) {
            return 0;
        }
    }
    
    @SuppressWarnings("unchecked")
    public static Queue<Object> getCompileResults(Object taskDispatcher) {
        if (!isAvailable() || taskDispatcher == null) {
            return null;
        }
        try {
            return (Queue<Object>) taskDispatcherCompileResultsField.get(taskDispatcher);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    /** Uploads one finished mesh. Has to be called with the owning renderer bound. */
    public static void doSectionUpdate(Object taskDispatcher, Object compileResult) {
        if (!isAvailable()) {
            return;
        }
        try {
            taskDispatcherDoSectionUpdate.invoke(taskDispatcher, compileResult);
        }
        catch (Throwable e) {
            reportOnce("upload section", e);
        }
    }
    
    /**
     * Frees the mesh of a finished result that will never be uploaded.
     *
     * <p>{@code UploadBuffer} holds LWJGL allocations, and {@code DrawBuffers.upload} - which releases
     * them when it is done copying - is never reached for a result whose chunk area no longer belongs to
     * any renderer this mod knows. {@code TaskDispatcher.doSectionUpdate} frees exactly these in its own
     * area mismatch branch, so dropping one without this leaks a section's worth of vertex memory.</p>
     */
    public static void releaseCompileResult(Object compileResult) {
        if (!isAvailable() || compileResult == null) {
            return;
        }
        try {
            Object layers = compileResultRenderedLayersField.get(compileResult);
            if (layers instanceof Map<?, ?>) {
                for (Object buffer : ((Map<?, ?>) layers).values()) {
                    if (buffer != null) {
                        uploadBufferRelease.invoke(buffer);
                    }
                }
            }
        }
        catch (Throwable e) {
            reportOnce("release an orphaned mesh", e);
        }
    }
    
    /** The chunk area a finished mesh was written for. */
    public static Object getCompileResultChunkArea(Object compileResult) {
        if (!isAvailable() || compileResult == null) {
            return null;
        }
        try {
            Object section = compileResultSectionField.get(compileResult);
            if (section == null) {
                return null;
            }
            return sectionGetChunkArea.invoke(section);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    public static int getChunkAreaIndex(Object chunkArea) {
        if (!isAvailable() || chunkArea == null) {
            return -1;
        }
        try {
            return chunkAreaIndexField.getInt(chunkArea);
        }
        catch (Throwable e) {
            return -1;
        }
    }
    
    /** The chunk area of {@code index} that this renderer actually owns, or null. */
    public static Object getOwnedChunkArea(Object worldRenderer, int index) {
        if (!isAvailable() || worldRenderer == null || index < 0) {
            return null;
        }
        try {
            Object manager = worldRendererGetChunkAreaManager.invoke(worldRenderer);
            if (manager == null) {
                return null;
            }
            return chunkAreaManagerGetArea.invoke(manager, index);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * Points a level renderer's terrain reference at another renderer. Immersive Portals creates
     * those renderers while the dimension they belong to is not the shared one, so without this the
     * portal would mesh and draw the world the player is standing in.
     */
    public static void setLevelRendererWorldRenderer(
        LevelRenderer levelRenderer, Object worldRenderer
    ) {
        if (!isAvailable() || levelRendererWorldRendererField == null) {
            return;
        }
        try {
            if (levelRendererWorldRendererField.get(levelRenderer) != worldRenderer) {
                levelRendererWorldRendererField.set(levelRenderer, worldRenderer);
            }
        }
        catch (Throwable e) {
            reportOnce("point level renderer at terrain renderer", e);
        }
    }
    
    public static Object getLevelRendererWorldRenderer(LevelRenderer levelRenderer) {
        if (!isAvailable() || levelRendererWorldRendererField == null || levelRenderer == null) {
            return null;
        }
        try {
            return levelRendererWorldRendererField.get(levelRenderer);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    public static Object getWorldRendererRenderBuffers(Object worldRenderer) {
        if (!isAvailable() || worldRenderer == null) {
            return null;
        }
        try {
            return worldRendererRenderBuffersField.get(worldRenderer);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * VulkanMod keeps the vertex buffers of the world it draws block entities from in the object it
     * was constructed with, so a portal pass that swapped Minecraft's buffers has to swap these too,
     * or block entities of the two dimensions write into the same builders.
     */
    public static void setWorldRendererRenderBuffers(Object worldRenderer, Object renderBuffers) {
        if (!isAvailable() || worldRenderer == null || renderBuffers == null) {
            return;
        }
        try {
            if (worldRendererRenderBuffersField.get(worldRenderer) != renderBuffers) {
                worldRendererRenderBuffersField.set(worldRenderer, renderBuffers);
            }
        }
        catch (Throwable e) {
            reportOnce("set renderer buffers", e);
        }
    }
    
    // ---- chunk status map ------------------------------------------------
    
    public static Object newChunkStatusMap(int renderDistance) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return chunkStatusMapConstructor.newInstance(
                Math.max(renderDistance, 1)
            );
        }
        catch (Throwable e) {
            reportOnce("new chunk status map", e);
            return null;
        }
    }
    
    public static Object getChunkStatusMap() {
        if (!isAvailable()) {
            return null;
        }
        try {
            return chunkStatusMapInstanceField.get(null);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    public static void setChunkStatusMap(Object chunkStatusMap) {
        if (!isAvailable()) {
            return;
        }
        try {
            chunkStatusMapInstanceField.set(null, chunkStatusMap);
        }
        catch (Throwable e) {
            reportOnce("set chunk status map", e);
        }
    }
    
    public static void chunkStatusSet(Object chunkStatusMap, int chunkX, int chunkZ, byte flag) {
        invokeChunkStatus(chunkStatusMapSet, chunkStatusMap, chunkX, chunkZ, flag, "set chunk status");
    }
    
    public static void chunkStatusReset(Object chunkStatusMap, int chunkX, int chunkZ, byte flag) {
        invokeChunkStatus(chunkStatusMapReset, chunkStatusMap, chunkX, chunkZ, flag, "reset chunk status");
    }
    
    public static boolean chunkStatusReady(Object chunkStatusMap, int chunkX, int chunkZ) {
        if (chunkStatusMap == null) {
            return false;
        }
        try {
            return (Boolean) chunkStatusMapReady.invoke(chunkStatusMap, chunkX, chunkZ);
        }
        catch (Throwable e) {
            return false;
        }
    }
    
    private static void invokeChunkStatus(
        Method method, Object chunkStatusMap, int chunkX, int chunkZ, byte flag, String what
    ) {
        if (chunkStatusMap == null) {
            return;
        }
        try {
            method.invoke(chunkStatusMap, chunkX, chunkZ, flag);
        }
        catch (Throwable e) {
            reportOnce(what, e);
        }
    }
    
    // ---- render sections -------------------------------------------------
    
    /** The field that holds VulkanMod's render sections, needed to mark chunks dirty again. */
    public static Object getSectionGrid(Object worldRenderer) {
        if (!isAvailable() || worldRenderer == null) {
            return null;
        }
        try {
            return sectionGridField.get(worldRenderer);
        }
        catch (Throwable e) {
            return null;
        }
    }
    
    public static void setGraphNeedsUpdate(Object worldRenderer, boolean value) {
        if (!isAvailable() || worldRenderer == null) {
            return;
        }
        try {
            graphNeedsUpdateField.setBoolean(worldRenderer, value);
        }
        catch (Throwable e) {
            reportOnce("set graph needs update", e);
        }
    }
    
    /** The sections covering a chunk column, including the ones of the eight neighbours. */
    @SuppressWarnings("unchecked")
    public static Iterable<Object> getRenderSectionsAt(Object sectionGrid, int chunkX, int chunkZ) {
        if (!isAvailable() || sectionGrid == null) {
            return Collections.emptyList();
        }
        try {
            return (Iterable<Object>) renderSectionsAtMethod.invoke(sectionGrid, chunkX, chunkZ);
        }
        catch (Throwable e) {
            return Collections.emptyList();
        }
    }
    
    public static void setSectionDirty(Object renderSection, boolean recursive) {
        if (!isAvailable() || renderSection == null) {
            return;
        }
        try {
            sectionSetDirty.invoke(renderSection, recursive);
        }
        catch (Throwable e) {
            reportOnce("set section dirty", e);
        }
    }
    
    private static String lastReportedFailure = null;
    
    private static void reportOnce(String what, Throwable e) {
        if (what.equals(lastReportedFailure)) {
            return;
        }
        lastReportedFailure = what;
        Helper.err("[Immersive Portals] VulkanMod access failed while trying to " + what + ": " + e);
    }
}
