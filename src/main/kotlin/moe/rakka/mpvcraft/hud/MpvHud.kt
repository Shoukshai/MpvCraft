package moe.rakka.mpvcraft.hud

import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.MpvCraft.mc
import moe.rakka.mpvcraft.mpv.MpvPlayer
import moe.rakka.mpvcraft.render.MpvPipRenderer
import moe.rakka.mpvcraft.render.MpvSubtitlePipRenderer
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Draws the two movable elements: the video surface and the subtitle block.
 *
 * Both are laid out in real window pixels. The pose is pre-scaled by
 * 1 / guiScale so that a coordinate here is a physical pixel, which is what
 * makes the layout independent of the player's GUI scale setting.
 */
object MpvHud {

    private const val OUTLINE = 0xBFFFFFFF.toInt()
    private const val OUTLINE_ACTIVE = 0xFF58C7FF.toInt()
    private const val SUB_BG = 0x99000000.toInt()
    private const val SUB_FG = 0xFFFFFFFF.toInt()

    val videoBox = HudBox(0, 0, 0, 0)
    val subBox = HudBox(0, 0, 0, 0)

    private val cfg get() = MpvCraft.config

    /** Height derived from the configured width and the video's aspect ratio. */
    fun videoHeight(): Int = (cfg.videoWidth / MpvPlayer.aspect()).toInt().coerceAtLeast(1)

    fun shouldShowVideo(): Boolean =
        cfg.videoEnabled && MpvPlayer.available && MpvPlayer.hasFile

    /** HUD layer callback, runs every frame while in game. */
    fun render(context: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (mc.level == null || mc.options.hideGui) return
        if (mc.screen is MpvHudScreen) return
        draw(context, editing = false)
    }

    fun draw(context: GuiGraphicsExtractor, editing: Boolean) {
        // FILE_LOADED/sid changes are handled lazily here on Minecraft's client
        // thread. Text subtitles stay in the MpvCraft HUD; supported local bitmap
        // tracks can use the transparent subtitle-only libmpv layer as well.
        MpvPlayer.syncSubtitlePresentation(cfg.subEnabled, cfg.subAttached)
        val showVideo = shouldShowVideo() || (editing && cfg.videoEnabled)
        val showDetachedImage = cfg.subEnabled && MpvPlayer.usesDetachedImageSubtitles
        val showTextSub = cfg.subEnabled && !MpvPlayer.usesImageSubtitles && (editing || MpvPlayer.subtitle.isNotEmpty())
        if (!showVideo && !showDetachedImage && !showTextSub) return

        context.pose().pushMatrix()
        val sf = mc.window.guiScale
        context.pose().scale(1f / sf, 1f / sf)

        if (showVideo) drawVideo(context, editing)
        drawSubtitles(context, editing)

        context.pose().popMatrix()
    }

    // ----------------------------------------------------------------

    private fun drawVideo(context: GuiGraphicsExtractor, editing: Boolean) {
        val w = cfg.videoWidth
        val h = videoHeight()

        videoBox.x = cfg.videoX
        videoBox.y = cfg.videoY
        videoBox.width = w
        videoBox.height = h

        if (MpvPlayer.hasFile) {
            MpvPipRenderer.draw(context, cfg.videoX, cfg.videoY, w, h)
        } else if (editing) {
            // Nothing loaded yet, show a placeholder so the box is still grabbable.
            context.fill(cfg.videoX, cfg.videoY, cfg.videoX + w, cfg.videoY + h, 0xCC101010.toInt())
            val label = "mpv - open media from /mpv"
            val tw = MpvUi.width(label)
            MpvUi.draw(
                context,
                label,
                cfg.videoX + (w - tw) / 2,
                cfg.videoY + h / 2 - MpvUi.lineHeight() / 2,
                SUB_FG,
                physicalPixels = true,
            )
        }

        if (editing) outline(context, videoBox, videoBox.contains(mouseX(), mouseY()))
    }

    private fun drawSubtitles(context: GuiGraphicsExtractor, editing: Boolean) {
        if (!cfg.subEnabled) return

        if (MpvPlayer.usesDetachedImageSubtitles) {
            drawDetachedImageSubtitles(context, editing)
            return
        }
        if (MpvPlayer.usesNativeImageSubtitles) return

        val text = MpvPlayer.subtitle.ifEmpty {
            if (editing) "Subtitles appear here" else return
        }

        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return

        val scale = cfg.subScale
        val lineHeight = MpvUi.lineHeight(MpvUi.SUBTITLE_SIZE) + 2
        val blockW = lines.maxOf { MpvUi.width(it, MpvUi.SUBTITLE_SIZE) }
        val blockH = lines.size * lineHeight

        val scaledW = (blockW * scale).toInt()
        val scaledH = (blockH * scale).toInt()

        // When attached, the block is centred under the video window.
        val originX: Int
        val originY: Int
        if (cfg.subAttached && cfg.videoEnabled) {
            originX = cfg.videoX + (cfg.videoWidth - scaledW) / 2
            originY = cfg.videoY + videoHeight() - scaledH - (12 * scale).toInt()
        } else {
            // A centred detached subtitle is an anchor mode, not a one-time X
            // coordinate. The width of a subtitle changes every cue.
            originX = if (cfg.subCenterX) {
                mc.window.screenWidth / 2 - scaledW / 2
            } else {
                cfg.subX
            }
            originY = cfg.subY
        }

        subBox.x = originX
        subBox.y = originY
        subBox.width = scaledW
        subBox.height = scaledH

        if (cfg.subBackground) {
            val pad = (4 * scale).toInt()
            context.fill(originX - pad, originY - pad, originX + scaledW + pad, originY + scaledH + pad, SUB_BG)
        }

        context.pose().pushMatrix()
        context.pose().translate(originX.toFloat(), originY.toFloat())
        context.pose().scale(scale, scale)
        lines.forEachIndexed { i, line ->
            val lx = (blockW - MpvUi.width(line, MpvUi.SUBTITLE_SIZE)) / 2
            MpvUi.draw(
                context,
                line,
                lx,
                i * lineHeight,
                SUB_FG,
                size = MpvUi.SUBTITLE_SIZE,
                physicalPixels = true,
                qualityScale = scale,
            )
        }
        context.pose().popMatrix()

        if (editing && !cfg.subAttached) outline(context, subBox, subBox.contains(mouseX(), mouseY()))
    }

    /**
     * PGS/VobSub/DVB/XSUB are rendered by a synchronized subtitle-only libmpv
     * core into a full transparent canvas. The canvas is screen-centred by
     * default so the bitmap keeps its authoring coordinates, but it can be moved
     * and scaled independently from the video in /mpv hud.
     */
    private fun drawDetachedImageSubtitles(context: GuiGraphicsExtractor, editing: Boolean) {
        val screenW = mc.window.screenWidth
        val screenH = mc.window.screenHeight
        val scale = cfg.imageSubScale.coerceIn(0.35f, 3f)
        val canvasW = (screenW * scale).toInt().coerceAtLeast(1)
        val canvasH = (screenH * scale).toInt().coerceAtLeast(1)
        val originX = (screenW - canvasW) / 2 + cfg.imageSubOffsetX
        val originY = (screenH - canvasH) / 2 + cfg.imageSubOffsetY

        subBox.x = originX
        subBox.y = originY
        subBox.width = canvasW
        subBox.height = canvasH

        MpvSubtitlePipRenderer.draw(context, originX, originY, canvasW, canvasH)
        if (editing) outline(context, subBox, subBox.contains(mouseX(), mouseY()))
    }

    private fun outline(context: GuiGraphicsExtractor, box: HudBox, hovered: Boolean) {
        val color = if (hovered) OUTLINE_ACTIVE else OUTLINE
        val t = if (hovered) 2 else 1
        context.fill(box.x - t, box.y - t, box.x + box.width + t, box.y, color)
        context.fill(box.x - t, box.y + box.height, box.x + box.width + t, box.y + box.height + t, color)
        context.fill(box.x - t, box.y, box.x, box.y + box.height, color)
        context.fill(box.x + box.width, box.y, box.x + box.width + t, box.y + box.height, color)
    }

    /**
     * Mouse position in the same space our boxes live in.
     *
     * Because the pose is scaled by 1 / guiScale, that space spans
     * window.screenWidth x window.screenHeight, which is exactly the space GLFW
     * reports the cursor in. So this is a straight pass-through.
     */
    fun mouseX(): Float = mc.mouseHandler.xpos().toFloat()

    fun mouseY(): Float = mc.mouseHandler.ypos().toFloat()
}
