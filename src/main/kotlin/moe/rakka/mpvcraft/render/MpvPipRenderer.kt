package moe.rakka.mpvcraft.render

import com.mojang.blaze3d.opengl.GlConst
import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import moe.rakka.mpvcraft.mpv.MpvPlayer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.joml.Matrix3x2f
import org.lwjgl.opengl.GL33C

/**
 * Bridges libmpv into the 26.x GUI pipeline.
 *
 * Since 1.21.6-ish, GuiGraphics does not draw immediately: it extracts render
 * states that get composited later, so there is no legal place to issue raw GL
 * in the middle of the HUD. PictureInPictureRenderer is the sanctioned escape
 * hatch: it hands us a freshly allocated colour texture plus its FBO, and
 * composites the result into the GUI at the right position.
 *
 * mpv renders into its own offscreen FBO, then we blit across. Handing mpv the
 * Minecraft framebuffer directly does not work: that target carries a depth
 * attachment and an internal format we do not control, and mpv assumes it owns
 * the target for the frame. The blit is a GPU-side copy, so the video still
 * never touches RAM.
 *
 * The structure of this class (state extraction, getFbo dance) is adapted from
 * Odin's NVGPIPRenderer by odtheking, BSD 3-Clause,
 * https://github.com/odtheking/OdinFabric
 */
class MpvPipRenderer(
    vertexConsumers: MultiBufferSource.BufferSource
) : PictureInPictureRenderer<MpvPipRenderer.MpvRenderState>(vertexConsumers) {

    override fun renderToTexture(state: MpvRenderState, poseStack: PoseStack) {
        if (!MpvPlayer.available) return

        val colorTex = RenderSystem.outputColorTextureOverride ?: return
        val dsa = (RenderSystem.getDevice().backend as? GlDevice)?.directStateAccess() ?: return
        val depthTex = (RenderSystem.outputDepthTextureOverride?.texture() as? GlTexture) ?: return

        val width = colorTex.getWidth(0)
        val height = colorTex.getHeight(0)
        if (width <= 0 || height <= 0) return

        val targetFbo = (colorTex.texture() as? GlTexture)?.getFbo(dsa, depthTex) ?: return

        // Rendering libmpv itself needs the expensive full state isolation, but the
        // cached video texture only changes at video frame rate. Do not pay that
        // cost at Minecraft's (potentially much higher) frame rate.
        val targetSizeChanged =
            MpvPlayer.targetWidth != width || MpvPlayer.targetHeight != height
        val redraw = MpvPlayer.hasPendingRedraw() || MpvPlayer.targetFbo == 0 || targetSizeChanged
        if (redraw) {
            GlStateGuard.guarded {
                // Keep mpv_render_context_update() under the same GL isolation as
                // render(). The outer pending check is atomic-only, so Minecraft
                // frames without a new video frame never pay for the full guard.
                MpvPlayer.consumePendingRedraw()
                MpvPlayer.renderFrame(width, height)
            }
        }

        if (MpvPlayer.targetFbo != 0) {
            // glBlitFramebuffer only needs framebuffer/scissor/sRGB state. Keep this
            // tiny so a 24/30 fps movie does not turn the 144/240 fps Minecraft
            // render loop into a pile of glGet* calls.
            val scissorWasEnabled = GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST)
            val srgbWasEnabled = GL33C.glIsEnabled(GL33C.GL_FRAMEBUFFER_SRGB)
            GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
            GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB)
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, MpvPlayer.targetFbo)
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, targetFbo)
            GL33C.glBlitFramebuffer(
                0, 0, MpvPlayer.targetWidth, MpvPlayer.targetHeight,
                0, 0, width, height,
                GL33C.GL_COLOR_BUFFER_BIT, GL33C.GL_LINEAR
            )
            if (scissorWasEnabled) GL33C.glEnable(GL33C.GL_SCISSOR_TEST)
            if (srgbWasEnabled) GL33C.glEnable(GL33C.GL_FRAMEBUFFER_SRGB)
        }

        // Hand the compositor back exactly the binding and viewport it expects.
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, targetFbo)
        GlStateManager._viewport(0, 0, width, height)
    }

    override fun getTranslateY(height: Int, windowScaleFactor: Int): Float = height / 2f
    override fun getRenderStateClass(): Class<MpvRenderState> = MpvRenderState::class.java
    override fun getTextureLabel(): String = "mpvcraft_video"

    data class MpvRenderState(
        private val x: Int,
        private val y: Int,
        private val width: Int,
        private val height: Int,
        private val scissor: ScreenRectangle?,
        private val bounds: ScreenRectangle?,
    ) : PictureInPictureRenderState {
        override fun scale(): Float = 1f
        override fun x0(): Int = x
        override fun y0(): Int = y
        override fun x1(): Int = x + width
        override fun y1(): Int = y + height
        override fun scissorArea(): ScreenRectangle? = scissor
        override fun bounds(): ScreenRectangle? = bounds
    }

    companion object {
        /**
         * Queues the video quad for drawing at the given rectangle.
         *
         * PictureInPictureRenderState has no pose field: x0/y0/x1/y1 must already
         * be in final GUI coordinates. MpvHud deliberately draws in physical-pixel
         * space by applying 1/guiScale to the pose, so forwarding the untransformed
         * physical rectangle made Minecraft apply guiScale a second time. The
         * result was exactly the observed 2x video / mismatched editor outline.
         */
        fun draw(context: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val scissor = context.scissorStack.peek()
            val pose = Matrix3x2f(context.pose())
            val rect = ScreenRectangle(x, y, width, height).transformMaxBounds(pose)
            val bounds = scissor?.intersection(rect) ?: rect

            context.guiRenderState.addPicturesInPictureState(
                MpvRenderState(
                    rect.left(),
                    rect.top(),
                    rect.width(),
                    rect.height(),
                    scissor,
                    bounds,
                )
            )
        }
    }
}
