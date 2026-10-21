// Part of the unofficial VulkanMod fork of Immersive Portals.
// Licensed under the Apache License, Version 2.0. See LICENSE and NOTICE.
package qouteall.imm_ptl.core.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.RenderBackendCompat;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Front clipping for renderers that have no clip plane.
 *
 * <p>The terrain of the world behind a portal is cut at the portal plane by Immersive Portals with
 * {@code GL_CLIP_PLANE0}, so that the part of it between the observer and the portal does not show
 * through. A renderer that replaces OpenGL has no fixed function clip plane to enable, so the same
 * plane is moved into the projection matrix instead: its third row is replaced by one that maps the
 * portal plane onto exactly the near plane, and the regular near plane clipping then discards what
 * the clip plane would have discarded.</p>
 *
 * <p>The matrix handed to a draw call is the one that is modified, only while the clip plane would
 * have been enabled. Sky, clouds, entities and the outer world keep their ordinary near plane, and
 * the geometry that survives is unchanged - an oblique near plane only removes depth range, it never
 * reveals geometry that the original frustum did not already contain.</p>
 */
public class ObliqueFrontClipping {
    
    /** The matrix currently carrying an oblique plane, or null when none does. */
    private static Matrix4f modified = null;
    
    private static float savedM02;
    private static float savedM12;
    private static float savedM22;
    private static float savedM32;
    
    private ObliqueFrontClipping() {}
    
    /**
     * Cuts {@code projectionMatrix} at the clipping plane that {@link FrontClipping} currently holds.
     * The plane is expressed in the space that this very matrix consumes, so it has to be applied to
     * the matrix that the draw call reads - not to {@code RenderSystem.getProjectionMatrix()}, which
     * a foreign renderer may have copied.
     */
    public static void apply(Matrix4f projectionMatrix) {
        // A matrix left sheared is worse than no clipping at all, because the very same instance is
        // handed to the sky, the clouds and the outer world afterwards. So put back whatever is
        // currently sheared before any of the bail-outs below, not only before a new plane is built.
        if (modified != null && modified != projectionMatrix) {
            restore();
        }
        
        if (!isApplicable()) {
            restore();
            return;
        }
        
        double[] plane = FrontClipping.getActiveClipPlaneEquationAfterModelView();
        double nx = plane[0];
        double ny = plane[1];
        double nz = plane[2];
        double nw = plane[3];
        
        // a scaled model view matrix leaves a plane that is not unit normal
        double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (normalLength < 1.0E-6) {
            restore();
            return;
        }
        nx /= normalLength;
        ny /= normalLength;
        nz /= normalLength;
        nw /= normalLength;
        
        // the observer has to be on the clipped side, otherwise the plane is behind it
        if (nw > -1.0E-4) {
            restore();
            return;
        }
        
        Matrix4f unmodified = new Matrix4f(projectionMatrix);
        if (modified == projectionMatrix) {
            // the row already carries an oblique plane, which must not be the base of the next one
            unmodified.m02(savedM02);
            unmodified.m12(savedM12);
            unmodified.m22(savedM22);
            unmodified.m32(savedM32);
        }
        
        if (Math.abs(unmodified.determinant()) < 1.0E-12) {
            restore();
            return;
        }
        Matrix4f inverse = unmodified.invert(new Matrix4f());
        
        // the corner of the far plane that reaches deepest past the clipping plane
        Vector4f corner = inverse.transform(
            new Vector4f(
                nx >= 0 ? 1f : -1f,
                ny >= 0 ? 1f : -1f,
                1f,
                1f
            )
        );
        if (Math.abs(corner.w()) < 1.0E-6) {
            restore();
            return;
        }
        double cornerX = corner.x() / corner.w();
        double cornerY = corner.y() / corner.w();
        double cornerZ = corner.z() / corner.w();
        
        double planeAtCorner = nx * cornerX + ny * cornerY + nz * cornerZ + nw;
        if (planeAtCorner < 1.0E-6) {
            // the whole frustum is on the clipped side: nothing would be drawn
            restore();
            return;
        }
        
        // the row that produces clip space w, which is minus the view space depth
        double wAtCorner =
            unmodified.m03() * cornerX +
                unmodified.m13() * cornerY +
                unmodified.m23() * cornerZ +
                unmodified.m33();
        
        // the new depth row is lambda * plane - w row, which puts clip space z at -w on the plane
        double lambda = 2 * wAtCorner / planeAtCorner;
        if (!Double.isFinite(lambda)) {
            restore();
            return;
        }
        
        if (modified == null) {
            savedM02 = projectionMatrix.m02();
            savedM12 = projectionMatrix.m12();
            savedM22 = projectionMatrix.m22();
            savedM32 = projectionMatrix.m32();
            modified = projectionMatrix;
        }
        
        projectionMatrix.m02((float) (lambda * nx - unmodified.m03()));
        projectionMatrix.m12((float) (lambda * ny - unmodified.m13()));
        projectionMatrix.m22((float) (lambda * nz - unmodified.m23()));
        projectionMatrix.m32((float) (lambda * nw - unmodified.m33()));
    }
    
    /** Puts the near plane back, so that whatever is drawn next uses the ordinary frustum. */
    public static void restore() {
        if (modified == null) {
            return;
        }
        modified.m02(savedM02);
        modified.m12(savedM12);
        modified.m22(savedM22);
        modified.m32(savedM32);
        modified = null;
    }
    
    /** Whether {@code matrix} currently carries an oblique near plane installed by this class. */
    public static boolean isModified(Matrix4f matrix) {
        return modified == matrix;
    }
    
    private static boolean isApplicable() {
        return !RenderBackendCompat.supportsFixedFunctionClipPlane() &&
            IPGlobal.enableClippingMechanism &&
            IPCGlobal.useFrontClipping &&
            PortalRendering.isRendering() &&
            FrontClipping.isClippingEnabled &&
            FrontClipping.getActiveClipPlaneEquationAfterModelView() != null;
    }
}
