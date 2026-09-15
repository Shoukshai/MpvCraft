package moe.rakka.mpvcraft.render

import com.mojang.blaze3d.opengl.GlConst
import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import moe.rakka.mpvcraft.mpv.MpvBitmapSubtitlePlayer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.joml.Matrix3x2f
import org.lwjgl.opengl.GL33C
import org.lwjgl.system.MemoryStack

/**
 * Composites detached bitmap subtitles into Minecraft's deferred GUI pipeline.
 *
 * V5.7 rendered a full-screen transparent PiP every Minecraft frame. Even when
 * the PGS bitmap itself was tiny, that meant clearing/blitting/compositing millions
 * of transparent pixels and was enough to hurt FPS badly.
 *
 * V5.7.2 keeps libmpv on a capped internal canvas, detects the alpha bounds of the
 * actual subtitle cue, and only asks Minecraft to composite that tight rectangle.
 */
class MpvSubtitlePipRenderer(
    vertexConsumers: MultiBufferSource.BufferSource,
) : PictureInPictureRenderer<MpvSubtitlePipRenderer.SubtitleRenderState>(vertexConsumers) {

    override fun renderToTexture(state: SubtitleRenderState, poseStack: PoseStack) {
        if (!MpvBitmapSubtitlePlayer.active) return

        val colorTex = RenderSystem.outputColorTextureOverride ?: return
        val dsa = (RenderSystem.getDevice().backend as? GlDevice)?.directStateAccess() ?: return
        val depthTex = (RenderSystem.outputDepthTextureOverride?.texture() as? GlTexture) ?: return
        val outW = colorTex.getWidth(0)
        val outH = colorTex.getHeight(0)
        if (outW <= 0 || outH <= 0) return
        val outputFbo = (colorTex.texture() as? GlTexture)?.getFbo(dsa, depthTex) ?: return

        val scissorWasEnabled = GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST)
        val srgbWasEnabled = GL33C.glIsEnabled(GL33C.GL_FRAMEBUFFER_SRGB)
        GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
        GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB)
        GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, outputFbo)
        MemoryStack.stackPush().use { stack ->
            GL33C.glClearBufferfv(GL33C.GL_COLOR, 0, stack.floats(0f, 0f, 0f, 0f))
        }

        val prepared = MpvBitmapSubtitlePlayer.prepareForRender()
        val canvasW = MpvBitmapSubtitlePlayer.renderCanvasWidth
        val canvasH = MpvBitmapSubtitlePlayer.renderCanvasHeight
        val targetSizeChanged =
            MpvBitmapSubtitlePlayer.targetWidth != canvasW || MpvBitmapSubtitlePlayer.targetHeight != canvasH
        val pending = MpvBitmapSubtitlePlayer.hasPendingRedraw()
        val force = MpvBitmapSubtitlePlayer.targetFbo == 0 || targetSizeChanged

        if (prepared && (force || pending) && MpvBitmapSubtitlePlayer.mayRenderNow(force)) {
            GlStateGuard.guarded {
                val mpvHasFrame = if (pending) MpvBitmapSubtitlePlayer.consumePendingRedraw() else false
                if (force || mpvHasFrame) {
                    MpvBitmapSubtitlePlayer.renderFrame(canvasW, canvasH)
                }
            }
        }

        // A 1x1 pump state keeps the helper progressing while no cue is visible.
        // It intentionally never copies the subtitle surface into Minecraft.
        if (!state.pumpOnly && MpvBitmapSubtitlePlayer.readyForDisplay && MpvBitmapSubtitlePlayer.targetFbo != 0) {
            val crop = MpvBitmapSubtitlePlayer.visibleCrop
            if (crop != null) {
                val srcX0 = crop.x.coerceIn(0, MpvBitmapSubtitlePlayer.targetWidth - 1)
                val srcX1 = (crop.x + crop.width).coerceIn(srcX0 + 1, MpvBitmapSubtitlePlayer.targetWidth)
                // Crop coordinates exposed to HUD are top-down; FBO coordinates are bottom-up.
                val srcY0 = (MpvBitmapSubtitlePlayer.targetHeight - (crop.y + crop.height))
                    .coerceIn(0, MpvBitmapSubtitlePlayer.targetHeight - 1)
                val srcY1 = (MpvBitmapSubtitlePlayer.targetHeight - crop.y)
                    .coerceIn(srcY0 + 1, MpvBitmapSubtitlePlayer.targetHeight)

                GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
                GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB)
                GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, MpvBitmapSubtitlePlayer.targetFbo)
                GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, outputFbo)
                GL33C.glBlitFramebuffer(
                    srcX0,
                    srcY0,
                    srcX1,
                    srcY1,
                    0,
                    0,
                    outW,
                    outH,
                    GL33C.GL_COLOR_BUFFER_BIT,
                    GL33C.GL_LINEAR,
                )
            }
        }

        if (scissorWasEnabled) GL33C.glEnable(GL33C.GL_SCISSOR_TEST) else GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
        if (srgbWasEnabled) GL33C.glEnable(GL33C.GL_FRAMEBUFFER_SRGB) else GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB)
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, outputFbo)
        GlStateManager._viewport(0, 0, outW, outH)
    }

    override fun getTranslateY(height: Int, windowScaleFactor: Int): Float = height / 2f
    override fun getRenderStateClass(): Class<SubtitleRenderState> = SubtitleRenderState::class.java
    override fun getTextureLabel(): String = "mpvcraft_bitmap_subtitles"

    data class SubtitleRenderState(
        private val x: Int,
        private val y: Int,
        private val width: Int,
        private val height: Int,
        val pumpOnly: Boolean,
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
        fun draw(context: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
            enqueue(context, x, y, width, height, pumpOnly = false)
        }

        /** Keep libmpv's subtitle helper alive while the current cue is transparent. */
        fun pump(context: GuiGraphicsExtractor) {
            enqueue(context, 0, 0, 1, 1, pumpOnly = true)
        }

        private fun enqueue(
            context: GuiGraphicsExtractor,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            pumpOnly: Boolean,
        ) {
            if (width <= 0 || height <= 0 || !MpvBitmapSubtitlePlayer.active) return
            val scissor = context.scissorStack.peek()
            val pose = Matrix3x2f(context.pose())
            val rect = ScreenRectangle(x, y, width, height).transformMaxBounds(pose)
            val bounds = scissor?.intersection(rect) ?: rect
            context.guiRenderState.addPicturesInPictureState(
                SubtitleRenderState(
                    rect.left(),
                    rect.top(),
                    rect.width(),
                    rect.height(),
                    pumpOnly,
                    scissor,
                    bounds,
                )
            )
        }
    }
}
