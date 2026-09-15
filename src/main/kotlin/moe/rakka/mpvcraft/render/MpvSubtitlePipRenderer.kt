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
 * Composites the transparent bitmap-subtitle libmpv surface into Minecraft's
 * deferred GUI pipeline. The PiP compositor performs the final alpha blend.
 */
class MpvSubtitlePipRenderer(
    vertexConsumers: MultiBufferSource.BufferSource,
) : PictureInPictureRenderer<MpvSubtitlePipRenderer.SubtitleRenderState>(vertexConsumers) {

    override fun renderToTexture(state: SubtitleRenderState, poseStack: PoseStack) {
        if (!MpvBitmapSubtitlePlayer.active) return

        val colorTex = RenderSystem.outputColorTextureOverride ?: return
        val dsa = (RenderSystem.getDevice().backend as? GlDevice)?.directStateAccess() ?: return
        val depthTex = (RenderSystem.outputDepthTextureOverride?.texture() as? GlTexture) ?: return
        val width = colorTex.getWidth(0)
        val height = colorTex.getHeight(0)
        if (width <= 0 || height <= 0) return
        val outputFbo = (colorTex.texture() as? GlTexture)?.getFbo(dsa, depthTex) ?: return

        // Always start transparent. This prevents stale subtitle frames while a
        // helper core is loading/seeking and makes unsupported alpha paths harmless.
        val scissorWasEnabled = GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST)
        val srgbWasEnabled = GL33C.glIsEnabled(GL33C.GL_FRAMEBUFFER_SRGB)
        GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
        GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB)
        GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, outputFbo)
        MemoryStack.stackPush().use { stack ->
            GL33C.glClearBufferfv(GL33C.GL_COLOR, 0, stack.floats(0f, 0f, 0f, 0f))
        }

        val prepared = MpvBitmapSubtitlePlayer.prepareForRender()
        val targetSizeChanged =
            MpvBitmapSubtitlePlayer.targetWidth != width || MpvBitmapSubtitlePlayer.targetHeight != height
        val redraw = prepared && (
            MpvBitmapSubtitlePlayer.hasPendingRedraw() ||
                MpvBitmapSubtitlePlayer.targetFbo == 0 ||
                targetSizeChanged
            )

        if (redraw) {
            GlStateGuard.guarded {
                MpvBitmapSubtitlePlayer.consumePendingRedraw()
                MpvBitmapSubtitlePlayer.renderFrame(width, height)
            }
        }

        if (MpvBitmapSubtitlePlayer.readyForDisplay && MpvBitmapSubtitlePlayer.targetFbo != 0) {
            GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
            GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB)
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, MpvBitmapSubtitlePlayer.targetFbo)
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, outputFbo)
            GL33C.glBlitFramebuffer(
                0,
                0,
                MpvBitmapSubtitlePlayer.targetWidth,
                MpvBitmapSubtitlePlayer.targetHeight,
                0,
                0,
                width,
                height,
                GL33C.GL_COLOR_BUFFER_BIT,
                GL33C.GL_LINEAR,
            )
        }

        if (scissorWasEnabled) GL33C.glEnable(GL33C.GL_SCISSOR_TEST) else GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
        if (srgbWasEnabled) GL33C.glEnable(GL33C.GL_FRAMEBUFFER_SRGB) else GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB)
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, outputFbo)
        GlStateManager._viewport(0, 0, width, height)
    }

    override fun getTranslateY(height: Int, windowScaleFactor: Int): Float = height / 2f
    override fun getRenderStateClass(): Class<SubtitleRenderState> = SubtitleRenderState::class.java
    override fun getTextureLabel(): String = "mpvcraft_bitmap_subtitles"

    data class SubtitleRenderState(
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
        fun draw(context: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
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
                    scissor,
                    bounds,
                )
            )
        }
    }
}
