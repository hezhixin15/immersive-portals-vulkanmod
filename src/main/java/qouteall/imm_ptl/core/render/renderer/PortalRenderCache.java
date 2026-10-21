// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.render.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.RenderBackendCompat;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.GuiPortalRendering;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.q_misc_util.Helper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One off-screen image per portal, so a frame can render a few portals live and paste the rest
 * from their previous image. A cached image is only a short-lived placeholder, made for the camera
 * pose of the frame it was rendered in, and is refreshed after {@link #MAX_AGE_NANOS} at the latest.
 */
public class PortalRenderCache {
    
    /**
     * How many portals may run a full destination-world pass in one frame.
     */
    public static final int LIVE_BUDGET = 2;
    
    private static final int MAX_SLOTS = 4;
    
    private static final long MAX_AGE_NANOS = 250_000_000L;
    
    // staggers the refresh deadline per slot so that a group of portals rendered together does not
    // all become stale in the same frame
    private static final long AGE_STAGGER_NANOS = 40_000_000L;
    
    private static final int STATS_SAMPLES = 200;
    
    // cross-portal view and GUI portal view render a world that is not the one this cache belongs
    // to, with its own camera; they keep using the old single-target behaviour
    private static boolean customViewActive = false;
    
    public static void beginCustomView() {
        customViewActive = true;
    }
    
    public static void endCustomView() {
        customViewActive = false;
    }
    
    public static final class Plan {
        public final Portal portal;
        public final RenderTarget target;
        public final boolean live;
        
        private final Slot slot;
        private final int stateHash;
        private final boolean customView;
        
        private Plan(
            Portal portal, RenderTarget target, boolean live,
            Slot slot, int stateHash, boolean customView
        ) {
            this.portal = portal;
            this.target = target;
            this.live = live;
            this.slot = slot;
            this.stateHash = stateHash;
            this.customView = customView;
        }
    }
    
    private static final class Slot {
        final int index;
        final SecondaryFrameBuffer framebuffer = new SecondaryFrameBuffer();
        WeakReference<Portal> portalRef;
        int stateHash;
        boolean hasImage;
        long lastLiveNanos;
        int lastSeenFrame;
        
        Slot(int index) {
            this.index = index;
        }
    }
    
    private final Slot[] slots = new Slot[MAX_SLOTS];
    private int slotCount = 1;
    private int preparedWidth;
    private int preparedHeight;
    private int frameSerial;
    private int lastVisibleCount;
    private boolean enabled;
    
    private int statsSamples;
    private long statsVisible;
    private long statsSkipped;
    private long statsLive;
    private long statsCached;
    
    public PortalRenderCache() {
        for (int i = 0; i < MAX_SLOTS; i++) {
            slots[i] = new Slot(i);
        }
    }
    
    /**
     * Called once per outer frame, before the world is rendered, so that creating or resizing the
     * targets never happens in the middle of a render pass.
     */
    public void prepare() {
        if (GuiPortalRendering.isRendering()) {
            return;
        }
        
        frameSerial++;
        enabled = RenderBackendCompat.IS_NON_GL_RENDERER;
        
        RenderTarget mainFrameBuffer = Minecraft.getInstance().getMainRenderTarget();
        if (mainFrameBuffer == null) {
            return;
        }
        int width = mainFrameBuffer.viewWidth;
        int height = mainFrameBuffer.viewHeight;
        if (width <= 0 || height <= 0) {
            return;
        }
        
        if (width != preparedWidth || height != preparedHeight) {
            boolean resized = preparedWidth > 0;
            preparedWidth = width;
            preparedHeight = height;
            for (Slot slot : slots) {
                slot.hasImage = false;
                slot.stateHash = 0;
            }
            Helper.log("[IP-VK-CACHE] portal targets "
                + (resized ? "resized to " : "sized ") + width + "x" + height);
        }
        
        int desired = enabled ? Math.min(MAX_SLOTS, Math.max(1, lastVisibleCount)) : 1;
        if (desired > slotCount) {
            slotCount = desired;
            Helper.log("[IP-VK-CACHE] slot pool grown to " + slotCount);
        }
        
        for (int i = 0; i < slotCount; i++) {
            slots[i].framebuffer.prepare(width, height);
        }
        
        // target creation and resizing leave another framebuffer bound
        mainFrameBuffer.bindWrite(true);
    }
    
    public List<Plan> plan(List<Portal> portals, Matrix4f modelView) {
        if (portals.isEmpty()) {
            lastVisibleCount = 0;
            return List.of();
        }
        
        if (customViewActive || GuiPortalRendering.isRendering()) {
            return planCustomView(portals);
        }
        
        if (!enabled) {
            return planAllLive(portals, slots[0], 0);
        }
        
        return planCached(portals, modelView);
    }
    
    private List<Plan> planCached(List<Portal> portals, Matrix4f modelView) {
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        List<Portal> renderPortals = portals.size() <= MAX_SLOTS
            ? PortalOcclusionCuller.cull(portals, cameraPos)
            : portals;
        int skipped = portals.size() - renderPortals.size();
        int visible = renderPortals.size();
        // the pool stays sized for every portal IP wants to draw, so a portal that comes out from
        // behind its occluder does not cost a fallback frame
        lastVisibleCount = portals.size();
        
        if (visible > slotCount) {
            // the pool cannot hold every portal, so there is no image to paste: play safe and use
            // the pre-cache single-target path for this frame
            invalidateSlot(slots[0]);
            return planAllLive(renderPortals, slots[0], skipped);
        }
        
        RenderTarget mainFrameBuffer = Minecraft.getInstance().getMainRenderTarget();
        int width = preparedWidth > 0 ? preparedWidth
            : (mainFrameBuffer != null ? mainFrameBuffer.viewWidth : 1);
        int height = preparedHeight > 0 ? preparedHeight
            : (mainFrameBuffer != null ? mainFrameBuffer.viewHeight : 1);
        
        Matrix4f mvp = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(modelView);
        
        float[] areas = new float[visible];
        for (int i = 0; i < visible; i++) {
            areas[i] = computeScreenArea(renderPortals.get(i), mvp, width, height, cameraPos);
        }
        
        Slot[] assigned = new Slot[visible];
        boolean[] taken = new boolean[slotCount];
        
        for (int i = 0; i < visible; i++) {
            Portal portal = renderPortals.get(i);
            for (int s = 0; s < slotCount; s++) {
                if (taken[s]) {
                    continue;
                }
                if (slotPortal(slots[s]) == portal) {
                    assigned[i] = slots[s];
                    taken[s] = true;
                    slots[s].lastSeenFrame = frameSerial;
                    break;
                }
            }
        }
        
        Integer[] byArea = new Integer[visible];
        for (int i = 0; i < visible; i++) {
            byArea[i] = i;
        }
        Arrays.sort(byArea, Comparator.comparingDouble((Integer i) -> -areas[i]));
        
        for (int index : byArea) {
            if (assigned[index] != null) {
                continue;
            }
            int slotIndex = findAssignableSlot(taken, renderPortals);
            if (slotIndex < 0) {
                invalidateSlot(slots[0]);
                return planAllLive(renderPortals, slots[0], skipped);
            }
            Slot slot = slots[slotIndex];
            assigned[index] = slot;
            taken[slotIndex] = true;
            slot.portalRef = new WeakReference<>(renderPortals.get(index));
            slot.hasImage = false;
            slot.stateHash = 0;
            slot.lastSeenFrame = frameSerial;
        }
        
        long now = System.nanoTime();
        long fullScreenArea = (long) width * height;
        boolean[] live = new boolean[visible];
        int[] hashes = new int[visible];
        int liveCount = 0;
        
        for (int i = 0; i < visible; i++) {
            Slot slot = assigned[i];
            hashes[i] = computeStateHash(renderPortals.get(i));
            long maxAge = MAX_AGE_NANOS + slot.index * AGE_STAGGER_NANOS;
            boolean stale = now - slot.lastLiveNanos > maxAge;
            if (!slot.hasImage || slot.stateHash != hashes[i] || stale
                || areas[i] > fullScreenArea * 0.5f
            ) {
                live[i] = true;
                liveCount++;
            }
        }
        
        // the remaining budget goes to the portals that cover the most screen
        for (int index : byArea) {
            if (liveCount >= LIVE_BUDGET) {
                break;
            }
            if (!live[index]) {
                live[index] = true;
                liveCount++;
            }
        }
        
        List<Plan> plans = new ArrayList<>(visible);
        int cachedCount = 0;
        for (int i = 0; i < visible; i++) {
            plans.add(new Plan(
                renderPortals.get(i), assigned[i].framebuffer.fb, live[i],
                assigned[i], hashes[i], false
            ));
            if (!live[i]) {
                cachedCount++;
            }
        }
        
        recordStats(portals.size(), skipped, liveCount, cachedCount);
        return plans;
    }
    
    private List<Plan> planAllLive(List<Portal> portals, Slot slot, int skipped) {
        ensureSlotTarget(slot);
        if (slot.framebuffer.fb == null) {
            return List.of();
        }
        List<Plan> plans = new ArrayList<>(portals.size());
        for (Portal portal : portals) {
            plans.add(new Plan(portal, slot.framebuffer.fb, true, null, 0, false));
        }
        recordStats(portals.size() + skipped, skipped, portals.size(), 0);
        return plans;
    }
    
    private List<Plan> planCustomView(List<Portal> portals) {
        Slot slot = slots[0];
        ensureSlotTarget(slot);
        if (slot.framebuffer.fb == null) {
            return List.of();
        }
        // the custom view overwrites this texture, so its portal image is gone
        invalidateSlot(slot);
        List<Plan> plans = new ArrayList<>(portals.size());
        for (Portal portal : portals) {
            plans.add(new Plan(portal, slot.framebuffer.fb, true, null, 0, true));
        }
        return plans;
    }
    
    public void markRendered(Plan plan) {
        if (plan.customView || plan.slot == null) {
            return;
        }
        plan.slot.hasImage = true;
        plan.slot.stateHash = plan.stateHash;
        plan.slot.lastLiveNanos = System.nanoTime();
        plan.slot.lastSeenFrame = frameSerial;
    }
    
    private void ensureSlotTarget(Slot slot) {
        if (slot.framebuffer.fb != null) {
            return;
        }
        RenderTarget mainFrameBuffer = Minecraft.getInstance().getMainRenderTarget();
        if (mainFrameBuffer == null) {
            return;
        }
        slot.framebuffer.prepare(mainFrameBuffer.viewWidth, mainFrameBuffer.viewHeight);
    }
    
    private static Portal slotPortal(Slot slot) {
        return slot.portalRef == null ? null : slot.portalRef.get();
    }
    
    private void invalidateSlot(Slot slot) {
        slot.portalRef = null;
        slot.hasImage = false;
        slot.stateHash = 0;
    }
    
    private int findAssignableSlot(boolean[] taken, List<Portal> visiblePortals) {
        int free = -1;
        int evict = -1;
        int evictSeenFrame = Integer.MAX_VALUE;
        
        for (int s = 0; s < slotCount; s++) {
            if (taken[s]) {
                continue;
            }
            Portal slotPortal = slotPortal(slots[s]);
            if (slotPortal == null) {
                if (free < 0) {
                    free = s;
                }
            }
            else if (!containsIdentity(visiblePortals, slotPortal)
                && slots[s].lastSeenFrame < evictSeenFrame
            ) {
                evictSeenFrame = slots[s].lastSeenFrame;
                evict = s;
            }
        }
        
        return free >= 0 ? free : evict;
    }
    
    private static boolean containsIdentity(List<Portal> portals, Portal portal) {
        for (Portal candidate : portals) {
            if (candidate == portal) {
                return true;
            }
        }
        return false;
    }
    
    private static int computeStateHash(Portal portal) {
        return Objects.hash(
            portal.getDestDim(),
            portal.getOriginPos(),
            portal.getDestPos(),
            portal.getScale(),
            portal.getWidth(),
            portal.getHeight(),
            portal.getRotation(),
            portal.getOrientationRotation(),
            portal.isFuseView(),
            portal instanceof Mirror
        );
    }
    
    private static float computeScreenArea(
        Portal portal, Matrix4f mvp, int width, int height, Vec3 cameraPos
    ) {
        AABB box = portal.getThinBoundingBox();
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? box.minX : box.maxX;
            double y = (corner & 2) == 0 ? box.minY : box.maxY;
            double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
            
            Vector4f point = new Vector4f(
                (float) (x - cameraPos.x),
                (float) (y - cameraPos.y),
                (float) (z - cameraPos.z),
                1.0f
            );
            mvp.transform(point);
            
            if (point.w <= 1.0e-4f) {
                // behind the near plane: the portal can cover any part of the screen
                return (float) width * height;
            }
            
            float screenX = (point.x / point.w * 0.5f + 0.5f) * width;
            float screenY = (point.y / point.w * 0.5f + 0.5f) * height;
            minX = Math.min(minX, screenX);
            minY = Math.min(minY, screenY);
            maxX = Math.max(maxX, screenX);
            maxY = Math.max(maxY, screenY);
        }
        
        minX = Math.max(minX, 0);
        minY = Math.max(minY, 0);
        maxX = Math.min(maxX, width);
        maxY = Math.min(maxY, height);
        
        if (maxX <= minX || maxY <= minY) {
            return 0;
        }
        return (maxX - minX) * (maxY - minY);
    }
    
    private void recordStats(int requested, int skipped, int live, int cached) {
        if (!enabled) {
            return;
        }
        statsSamples++;
        statsVisible += requested;
        statsSkipped += skipped;
        statsLive += live;
        statsCached += cached;
        
        if (statsSamples >= STATS_SAMPLES) {
            Helper.log(String.format(
                "[IP-VK-CACHE] samples=%d avgVisible=%.2f avgSkipped=%.2f avgLive=%.2f"
                    + " avgCached=%.2f slots=%d",
                statsSamples,
                statsVisible / (double) statsSamples,
                statsSkipped / (double) statsSamples,
                statsLive / (double) statsSamples,
                statsCached / (double) statsSamples,
                slotCount
            ));
            statsSamples = 0;
            statsVisible = 0;
            statsSkipped = 0;
            statsLive = 0;
            statsCached = 0;
        }
    }
}
