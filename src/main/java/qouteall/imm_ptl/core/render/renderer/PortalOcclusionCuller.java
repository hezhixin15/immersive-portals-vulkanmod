// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.render.renderer;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.animation.UnilateralPortalState;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.imm_ptl.core.render.FrustumCuller;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.q_misc_util.Helper;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops a portal when a nearer portal's opening completely covers it.
 * <p>
 * VulkanMod has no occlusion query, so a portal sitting right behind another one still runs its own
 * destination pass even though the nearer portal's opaque area hides every pixel of it. A portal is
 * only dropped when its whole thin bounding box is strictly behind the nearer portal's plane and
 * inside a slightly shrunk copy of that portal's opening; anything uncertain keeps its pass.
 */
final class PortalOcclusionCuller {
    
    // the occluder's opening is shrunk by this fraction on each side, so a target touching the
    // occluder's border is not treated as hidden
    private static final double OPENING_MARGIN = 0.02;
    
    // the target must be at least this far behind the occluder's plane, so depth precision and
    // equal-depth surfaces cannot leave a visible sliver
    private static final double DEPTH_MARGIN = 0.05;
    
    private static boolean loggedFirstCull = false;
    
    private PortalOcclusionCuller() {}
    
    static List<Portal> cull(List<Portal> portalsNearToFar, Vec3 cameraPos) {
        if (portalsNearToFar.size() < 2 || TransformationManager.isIsometricView) {
            return portalsNearToFar;
        }
        
        List<Portal> kept = new ArrayList<>(portalsNearToFar.size());
        List<Occluder> occluders = new ArrayList<>(portalsNearToFar.size());
        
        int hiddenCount = 0;
        for (Portal portal : portalsNearToFar) {
            if (isHidden(portal, cameraPos, occluders)) {
                hiddenCount++;
                continue;
            }
            kept.add(portal);
            if (canBeOccluder(portal)) {
                Occluder occluder = Occluder.of(portal, cameraPos);
                if (occluder != null) {
                    occluders.add(occluder);
                }
            }
        }
        
        if (hiddenCount != 0 && !loggedFirstCull) {
            loggedFirstCull = true;
            Helper.log(
                "[IP-VK-CULL] " + hiddenCount + " of " + portalsNearToFar.size()
                    + " portal(s) fully covered by a nearer portal, skipped"
            );
        }
        
        return kept;
    }
    
    private static boolean isHidden(Portal portal, Vec3 cameraPos, List<Occluder> occluders) {
        for (int i = 0; i < occluders.size(); i++) {
            if (occluders.get(i).hides(portal, cameraPos)) {
                return true;
            }
        }
        return false;
    }
    
    private static boolean canBeOccluder(Portal portal) {
        // mirrors shift their area by a hair, and fuse-view portals do not draw a normal opaque
        // area; neither is a dependable occluder or a dependable target
        return !(portal instanceof Mirror)
            && !portal.isFuseView()
            && portal.getPortalShape() instanceof RectangularPortalShape;
    }
    
    private static final class Occluder {
        private final FrustumCuller.Frustum4Planes opening;
        private final Vec3 planeNormal;
        private final double planeW;
        
        private Occluder(FrustumCuller.Frustum4Planes opening, Vec3 planeNormal, double planeW) {
            this.opening = opening;
            this.planeNormal = planeNormal;
            this.planeW = planeW;
        }
        
        static Occluder of(Portal portal, Vec3 cameraPos) {
            UnilateralPortalState state = portal.getThisSideState();
            Vec3 originRel = state.position().subtract(cameraPos);
            Vec3 axisW = state.getAxisW();
            Vec3 axisH = state.getAxisH();
            double halfWidth = state.width() / 2 * (1 - OPENING_MARGIN);
            double halfHeight = state.height() / 2 * (1 - OPENING_MARGIN);
            
            // same corner order as FrustumCuller.getRectPortalFourVerticesCounterClockwise
            Vec3[] corners = new Vec3[]{
                originRel.add(axisW.scale(halfWidth)).add(axisH.scale(-halfHeight)),
                originRel.add(axisW.scale(halfWidth)).add(axisH.scale(halfHeight)),
                originRel.add(axisW.scale(-halfWidth)).add(axisH.scale(halfHeight)),
                originRel.add(axisW.scale(-halfWidth)).add(axisH.scale(-halfHeight))
            };
            
            Vec3 normal = state.getNormal();
            return new Occluder(
                FrustumCuller.getFrustumPlanesFromFourVerticesCounterClockwise(corners),
                normal,
                -originRel.dot(normal)
            );
        }
        
        boolean hides(Portal target, Vec3 cameraPos) {
            AABB box = target.getThinBoundingBox();
            float minX = (float) (box.minX - cameraPos.x);
            float minY = (float) (box.minY - cameraPos.y);
            float minZ = (float) (box.minZ - cameraPos.z);
            float maxX = (float) (box.maxX - cameraPos.x);
            float maxY = (float) (box.maxY - cameraPos.y);
            float maxZ = (float) (box.maxZ - cameraPos.z);
            
            // corner closest to the occluder's plane has to be behind it by the margin
            double nearX = planeNormal.x > 0 ? maxX : minX;
            double nearY = planeNormal.y > 0 ? maxY : minY;
            double nearZ = planeNormal.z > 0 ? maxZ : minZ;
            double distance = nearX * planeNormal.x
                + nearY * planeNormal.y
                + nearZ * planeNormal.z
                + planeW;
            if (distance > -DEPTH_MARGIN) {
                return false;
            }
            
            return opening.isFullyInside(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
