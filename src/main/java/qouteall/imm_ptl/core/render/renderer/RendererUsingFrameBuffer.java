// Modified for the unofficial VulkanMod fork of Immersive Portals (see NOTICE).
package qouteall.imm_ptl.core.render.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.compat.RenderBackendCompat;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.QueryManager;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.List;

public class RendererUsingFrameBuffer extends PortalRenderer {
    private final PortalRenderCache portalRenderCache = new PortalRenderCache();
    
    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
        renderPortals(modelView);
    }
    
    @Override
    public void onAfterTranslucentRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void onHandRenderingEnded() {
    
    }
    
    @Override
    public void finishRendering() {
    
    }
    
    @Override
    public void prepareRendering() {
        portalRenderCache.prepare();
        
        GlStateManager._enableDepthTest();
        
        if (RenderBackendCompat.supportsStencil()) {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            
            IPPortingLibCompat.setIsStencilEnabled(client.getMainRenderTarget(), false);
        }
//        ((IEFrameBuffer) client.getMainRenderTarget()).setIsStencilBufferEnabledAndReload(false);
    }
    
    protected void doRenderPortal(
        Portal portal,
        Matrix4f modelView,
        PortalRenderCache.Plan plan
    ) {
        if (PortalRendering.isRendering()) {
            //only support one-layer portal
            return;
        }
        
        if (!testShouldRenderPortal(portal, modelView)) {
            return;
        }
        
        if (plan.live) {
            PortalRendering.pushPortalLayer(portal);
            
            RenderTarget oldFrameBuffer = client.getMainRenderTarget();
            
            ((IEMinecraftClient) client).ip_setFrameBuffer(plan.target);
            plan.target.bindWrite(true);
            
            float[] previousClearColor = RenderBackendCompat.getClearColor();
            GlStateManager._clearColor(1, 0, 1, 1);
            GlStateManager._clearDepth(1);
            GlStateManager._clear(
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT,
                Minecraft.ON_OSX
            );
            if (previousClearColor != null) {
                // magenta is only wanted in this attachment. VulkanMod reads the same process-wide clear
                // colour when it begins the swapchain pass, so leaving it set tints the next frame and
                // shows up as magenta specks wherever the portal area blends over what was there before
                GlStateManager._clearColor(
                    previousClearColor[0], previousClearColor[1],
                    previousClearColor[2], previousClearColor[3]
                );
            }
            if (RenderBackendCompat.supportsStencil()) {
                GL11.glDisable(GL11.GL_STENCIL_TEST);
            }
            
            renderPortalContent(portal);
            
            ((IEMinecraftClient) client).ip_setFrameBuffer(oldFrameBuffer);
            oldFrameBuffer.bindWrite(true);
            
            PortalRendering.popPortalLayer();
            
            portalRenderCache.markRendered(plan);
        }
        
        CHelper.enableDepthClamp();
        renderSecondBufferIntoMainBuffer(portal, modelView, plan.target);
        CHelper.disableDepthClamp();
        
        MyRenderHelper.debugFramebufferDepth();
    }
    
    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
        //nothing
    }
    
    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }
    
    private boolean testShouldRenderPortal(
        Portal portal,
        Matrix4f modelView
    ) {
        FrontClipping.updateInnerClipping(modelView);
        if (!RenderBackendCompat.supportsOcclusionQuery()) {
            // the query is only an optimization, but drawing the portal's own volume to find out
            // whether anything is behind it is not: without a query to keep it off the image, that
            // geometry would land on the screen
            return true;
        }
        return QueryManager.renderAndGetDoesAnySamplePass(() -> {
            ViewAreaRenderer.renderPortalArea(
                portal, Vec3.ZERO,
                modelView,
                RenderSystem.getProjectionMatrix(),
                true, true,
                true, true
            );
        });
    }
    
    private void renderSecondBufferIntoMainBuffer(
        Portal portal,
        Matrix4f modelView,
        RenderTarget source
    ) {
        MyRenderHelper.drawPortalAreaWithFramebuffer(
            portal,
            source,
            modelView,
            RenderSystem.getProjectionMatrix()
        );
    }
    
    protected void renderPortals(Matrix4f modelView) {
        if (PortalRendering.isRendering()) {
            //only support one-layer portal
            return;
        }
        
        List<Portal> portalsToRender = getPortalsToRender(modelView);
        
        for (PortalRenderCache.Plan plan : portalRenderCache.plan(portalsToRender, modelView)) {
            doRenderPortal(plan.portal, modelView, plan);
        }
    }
}
