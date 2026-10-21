// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.compat.vulkanmod_compatibility;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import qouteall.imm_ptl.core.compat.RenderBackendCompat;

/**
 * Makes VulkanMod's chunk readiness table dimension aware.
 *
 * <p>VulkanMod stores, for every chunk column, whether the block data and the light data have
 * arrived, and only builds terrain for columns that reached the ready state. That table is keyed by
 * chunk position without a dimension and is a single global instance, because VulkanMod assumes one
 * world exists at a time. Immersive Portals keeps several client worlds alive in the same frame, so
 * without separating the table, a chunk that arrives for the nether would mark the same coordinates
 * ready in the overworld, an empty section would be built for a column that has no data, and the
 * section would then never be revisited - a permanent hole.</p>
 *
 * <p>VulkanMod writes the table from four injection points in vanilla chunk loading and reads it
 * from one place in its section graph, all through {@code ChunkStatusMap.INSTANCE}. The instance
 * field is public and non final, so it is replaced by a dispatcher whose calls are forwarded to a
 * per dimension table. Reads resolve the dimension of the terrain renderer that is currently bound,
 * writes resolve the dimension of the world the chunk packet is being applied to, which is recorded
 * by {@code MixinVulkanModClientPacketListener} for light data and passed in explicitly by
 * {@code ImmPtlClientChunkMap} for block data, because Immersive Portals replaces that class
 * entirely. Both fall back to the dimension the player is in.</p>
 */
public class VulkanChunkStatusRouter {
    
    /** Byte flags of {@code net.vulkanmod.render.chunk.ChunkStatusMap}. */
    public static final byte FLAG_DATA_READY = 1;
    public static final byte FLAG_ALL = 7;
    
    private static Object dispatcher;
    
    private static final Object2ObjectOpenHashMap<ResourceKey<Level>, Object> tables =
        new Object2ObjectOpenHashMap<>();
    
    /** The dimension whose terrain is about to be drawn. */
    private static ResourceKey<Level> boundDimension = null;
    
    /** The dimension a chunk packet currently belongs to, or null to use the player's. */
    private static ResourceKey<Level> writeDimension = null;
    
    /** Set while Immersive Portals performs a write on behalf of a specific world. */
    private static ResourceKey<Level> explicitDimension = null;
    
    private VulkanChunkStatusRouter() {}
    
    // ---- installation ----------------------------------------------------
    
    /**
     * Called instead of {@code ChunkStatusMap.createInstance}. Returns false when the mod could not
     * be driven, so that VulkanMod's own behaviour stays intact.
     *
     * <p>The per dimension tables are deliberately not cleared here. VulkanMod calls this from
     * {@code WorldRenderer.setLevel} whenever it rebinds its renderer, which for Immersive Portals
     * happens while other worlds are still loaded; wiping the readiness of a world whose chunks the
     * server will not send again would leave that world permanently unbuildable.</p>
     */
    public static boolean ensureDispatcher(int renderDistance) {
        if (!RenderBackendCompat.IS_NON_GL_RENDERER) {
            return false;
        }
        Object current = VulkanModAccess.getChunkStatusMap();
        if (current != null && current == dispatcher) {
            return true;
        }
        if (current != null && dispatcher == null && !tables.isEmpty()) {
            // should not happen, but never drop a table that is still referenced
            return false;
        }
        Object newDispatcher = VulkanModAccess.newChunkStatusMap(renderDistance);
        if (newDispatcher == null) {
            return false;
        }
        dispatcher = newDispatcher;
        VulkanModAccess.setChunkStatusMap(newDispatcher);
        return true;
    }
    
    public static boolean isDispatcher(Object chunkStatusMap) {
        return chunkStatusMap != null && chunkStatusMap == dispatcher;
    }
    
    // ---- dimensions ------------------------------------------------------
    
    public static void setBoundDimension(ResourceKey<Level> dimension) {
        boundDimension = dimension;
    }
    
    public static ResourceKey<Level> getBoundDimension() {
        ResourceKey<Level> result = boundDimension;
        if (result == null) {
            result = currentPlayerDimension();
        }
        return result;
    }
    
    /** Recorded before vanilla applies chunk data, so that the write reaches the right world. */
    public static void setWriteDimension(ResourceKey<Level> dimension) {
        writeDimension = dimension;
    }
    
    private static ResourceKey<Level> currentWriteDimension() {
        if (explicitDimension != null) {
            return explicitDimension;
        }
        ResourceKey<Level> result = writeDimension;
        if (result == null) {
            result = currentPlayerDimension();
        }
        return result;
    }
    
    private static ResourceKey<Level> currentPlayerDimension() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.dimension();
    }
    
    /**
     * Runs {@code action} with every table access, read included, aimed at {@code dimension}. Used
     * when Immersive Portals itself reports chunk state for a world that is not being drawn.
     */
    public static void withDimension(ResourceKey<Level> dimension, Runnable action) {
        ResourceKey<Level> previous = explicitDimension;
        explicitDimension = dimension;
        try {
            action.run();
        }
        finally {
            explicitDimension = previous;
        }
    }
    
    // ---- forwarding, called by MixinVulkanModChunkStatusMap --------------
    
    public static void forwardSet(int chunkX, int chunkZ, byte flag) {
        ResourceKey<Level> dimension = currentWriteDimension();
        Object table = tableFor(dimension);
        boolean[] readyBefore = readinessAround(table, chunkX, chunkZ);
        VulkanModAccess.chunkStatusSet(table, chunkX, chunkZ, flag);
        reDirtyNewlyReadyChunks(dimension, table, chunkX, chunkZ, readyBefore);
    }
    
    public static void forwardReset(int chunkX, int chunkZ, byte flag) {
        VulkanModAccess.chunkStatusReset(tableFor(currentWriteDimension()), chunkX, chunkZ, flag);
    }
    
    public static boolean forwardIsReady(int chunkX, int chunkZ) {
        ResourceKey<Level> dimension = explicitDimension != null
            ? explicitDimension
            : getBoundDimension();
        return VulkanModAccess.chunkStatusReady(tableFor(dimension), chunkX, chunkZ);
    }
    
    /**
     * VulkanMod's {@code SectionGraph.scheduleRebuilds()} calls {@code setNotDirty()} on a section
     * even when {@code createCompileTask()} returned null because the chunk was not render-ready
     * yet. Such a section is never scheduled again and stays an invisible hole forever, so every
     * transition into the ready state has to put the sections of that chunk back on the dirty list.
     * {@code setChunkStatus} also promotes or demotes the eight neighbours via
     * {@code updateNeighbours}, hence the 3x3 sweep.
     */
    private static void reDirtyNewlyReadyChunks(
        ResourceKey<Level> dimension, Object table, int x, int z, boolean[] readyBefore
    ) {
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (VulkanModAccess.chunkStatusReady(table, x + dx, z + dz) && !readyBefore[i]) {
                    VulkanTerrainBridge.reDirtySections(dimension, x + dx, z + dz);
                }
                i++;
            }
        }
    }
    
    private static boolean[] readinessAround(Object table, int x, int z) {
        boolean[] result = new boolean[9];
        if (table == null) {
            return result;
        }
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                result[i++] = VulkanModAccess.chunkStatusReady(table, x + dx, z + dz);
            }
        }
        return result;
    }
    
    private static Object tableFor(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return null;
        }
        Object table = tables.get(dimension);
        if (table == null) {
            table = VulkanModAccess.newChunkStatusMap(8);
            if (table == null) {
                return null;
            }
            tables.put(dimension, table);
        }
        return table;
    }
    
    /** Drops the table of a world that is gone, so that a re-entered world starts clean. */
    public static void invalidate(ResourceKey<Level> dimension) {
        tables.remove(dimension);
    }
    
    // ---- filling ---------------------------------------------------------
    
    /**
     * Marks every chunk the client already holds as ready. VulkanMod only learns about a chunk from
     * a packet, and a world that was loaded a while ago will not be sent again, so a table created
     * now would stay empty and the world would build no terrain at all.
     */
    public static void markLoadedChunksReady(ClientLevel level) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        ResourceKey<Level> dimension = level.dimension();
        int radius = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
        ChunkPos center = new ChunkPos(Minecraft.getInstance().player.blockPosition());
        
        int marked = 0;
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    VulkanModAccess.chunkStatusSet(tableFor(dimension), x, z, FLAG_ALL);
                    marked++;
                }
            }
        }
        if (marked > 0) {
            qouteall.q_misc_util.Helper.log(
                "[Immersive Portals] marked " + marked + " already loaded chunks of " +
                    dimension.location() + " ready for VulkanMod"
            );
        }
    }
}
