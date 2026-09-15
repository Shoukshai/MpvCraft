package moe.rakka.mpvcraft.hud

import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.MpvCraft.mc
import moe.rakka.mpvcraft.mpv.MpvPlayer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * Mouse-only HUD layout editor.
 *
 * Text subtitles keep their horizontal centre snap. Bitmap subtitles use a
 * different model: mpv renders the complete authored subtitle canvas (PGS,
 * VobSub, DVB, XSUB) and the editor moves/scales that canvas as one unit.
 */
object MpvHudScreen : Screen(MpvUi.text("MpvCraft HUD layout")) {

    private enum class Target { NONE, VIDEO, SUBS }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double): Boolean = px >= x && py >= y && px < x + w && py < y + h
    }

    private const val CENTER_SNAP_PX = 14
    private val GUIDE = 0x9058C7FF.toInt()
    private val GUIDE_SNAPPED = 0xE077D69A.toInt()
    private val TEXT = 0xFFFFFFFF.toInt()
    private val MUTED = 0xFFB6C0CC.toInt()
    private val SURFACE = 0xE61B2630.toInt()
    private val SURFACE_HOVER = 0xF02A3D4A.toInt()
    private val BORDER = 0x80526A79.toInt()
    private val ACCENT = 0xFF2DA8F4.toInt()

    private var dragging = Target.NONE
    private var grabX = 0f
    private var grabY = 0f
    private var subtitleCentered = false

    private val cfg get() = MpvCraft.config
    private fun doneRect() = Rect(width - 80, 12, 68, 25)

    override fun init() {
        subtitleCentered = cfg.subCenterX
        clampAll()
    }

    override fun extractRenderState(
        guiGraphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        deltaTicks: Float,
    ) {
        subtitleCentered = cfg.subCenterX
        when (dragging) {
            Target.VIDEO -> {
                cfg.videoX = (MpvHud.mouseX() + grabX).toInt()
                cfg.videoY = (MpvHud.mouseY() + grabY).toInt()
            }
            Target.SUBS -> {
                if (MpvPlayer.usesDetachedImageSubtitles) {
                    val sf = cfg.imageSubScale.coerceIn(0.35f, 3f)
                    val canvasW = (mc.window.screenWidth * sf).toInt().coerceAtLeast(1)
                    val canvasH = (mc.window.screenHeight * sf).toInt().coerceAtLeast(1)
                    val desiredX = (MpvHud.mouseX() + grabX).toInt()
                    val desiredY = (MpvHud.mouseY() + grabY).toInt()
                    cfg.imageSubOffsetX = desiredX - (mc.window.screenWidth - canvasW) / 2
                    cfg.imageSubOffsetY = desiredY - (mc.window.screenHeight - canvasH) / 2
                } else {
                    cfg.subX = (MpvHud.mouseX() + grabX).toInt()
                    cfg.subY = (MpvHud.mouseY() + grabY).toInt()
                    applySubtitleCenterSnap()
                }
            }
            Target.NONE -> Unit
        }
        clampAll()

        MpvHud.draw(guiGraphics, editing = true)
        if (dragging == Target.SUBS && !cfg.subAttached && !MpvPlayer.usesImageSubtitles) {
            drawSubtitleCenterGuide(guiGraphics)
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, deltaTicks)
        drawEditorChrome(guiGraphics)
        drawDoneButton(guiGraphics, mouseX, mouseY)
    }

    private fun applySubtitleCenterSnap() {
        if (MpvPlayer.usesImageSubtitles) return
        val width = MpvHud.subBox.width.coerceAtLeast(1)
        val screenCenter = mc.window.screenWidth / 2
        val subtitleCenter = cfg.subX + width / 2
        subtitleCentered = abs(subtitleCenter - screenCenter) <= CENTER_SNAP_PX
        cfg.subCenterX = subtitleCentered
        if (subtitleCentered) cfg.subX = screenCenter - width / 2
    }

    /** One vertical line only: X centering aid, no Y-axis snapping/guide. */
    private fun drawSubtitleCenterGuide(g: GuiGraphicsExtractor) {
        g.pose().pushMatrix()
        val sf = mc.window.guiScale
        g.pose().scale(1f / sf, 1f / sf)
        val x = mc.window.screenWidth / 2
        val color = if (subtitleCentered) GUIDE_SNAPPED else GUIDE
        val thickness = if (subtitleCentered) 2 else 1
        g.fill(x - thickness / 2, 0, x - thickness / 2 + thickness, mc.window.screenHeight, color)
        g.pose().popMatrix()
    }

    private fun drawEditorChrome(g: GuiGraphicsExtractor) {
        val note = when {
            MpvPlayer.usesDetachedImageSubtitles ->
                "Drag detached image subtitles to move - scroll to scale"
            MpvPlayer.usesNativeImageSubtitles && !cfg.subAttached ->
                "Image subtitle detach unavailable for this source - native fallback"
            MpvPlayer.usesNativeImageSubtitles ->
                "Image subtitles are attached - disable Attached Subs to detach"
            cfg.subAttached ->
                "Drag video to move - scroll to resize - subtitles are attached"
            else ->
                "Drag to move - scroll to resize - subtitle guide snaps X to centre"
        }
        val maxW = (width - 112).coerceAtLeast(180)
        val cardW = (MpvUi.width(note, size = 10) + 22).coerceAtMost(maxW)
        val x = 12
        val y = 12
        MpvUi.roundedRect(g, x, y, cardW, 40, 12, 0xE6151E27.toInt(), 0x704B6170, 1f)
        MpvUi.roundedRect(g, x + 5, y + 8, 3, 24, 2, ACCENT)
        MpvUi.draw(g, "HUD layout", x + 14, y + 6, TEXT, size = 12, bold = true)
        MpvUi.draw(g, MpvUi.clip(note, cardW - 26, size = 9), x + 14, y + 23, MUTED, size = 9)
    }

    private fun drawDoneButton(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val r = doneRect()
        val hovered = r.contains(mouseX.toDouble(), mouseY.toDouble())
        MpvUi.roundedRect(
            g,
            r.x,
            r.y,
            r.w,
            r.h,
            10,
            if (hovered) SURFACE_HOVER else SURFACE,
            if (hovered) ACCENT else BORDER,
            1f,
        )
        MpvUi.drawCentered(g, "Done", r.x + r.w / 2, r.y + 6, TEXT, size = 10, bold = true)
    }

    private fun hovered(): Target {
        val mx = MpvHud.mouseX()
        val my = MpvHud.mouseY()
        if (cfg.subEnabled && !cfg.subAttached && MpvPlayer.usesDetachedImageSubtitles && MpvHud.subBox.contains(mx, my)) {
            return Target.SUBS
        }
        if (cfg.subEnabled && !cfg.subAttached && !MpvPlayer.usesImageSubtitles && MpvHud.subBox.contains(mx, my)) {
            return Target.SUBS
        }
        if (cfg.videoEnabled && MpvHud.videoBox.contains(mx, my)) return Target.VIDEO
        return Target.NONE
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean): Boolean {
        if (mouseButtonEvent.button() != 0) return super.mouseClicked(mouseButtonEvent, doubled)
        val mxGui = mouseButtonEvent.x()
        val myGui = mouseButtonEvent.y()
        if (doneRect().contains(mxGui, myGui)) {
            onClose()
            return true
        }

        val target = hovered()
        if (target != Target.NONE) {
            dragging = target
            val mx = MpvHud.mouseX()
            val my = MpvHud.mouseY()
            when (target) {
                Target.VIDEO -> {
                    grabX = cfg.videoX - mx
                    grabY = cfg.videoY - my
                }
                Target.SUBS -> {
                    if (MpvPlayer.usesDetachedImageSubtitles) {
                        grabX = MpvHud.subBox.x - mx
                        grabY = MpvHud.subBox.y - my
                    } else {
                        cfg.subX = MpvHud.subBox.x
                        cfg.subCenterX = false
                        subtitleCentered = false
                        grabX = cfg.subX - mx
                        grabY = cfg.subY - my
                    }
                }
                Target.NONE -> Unit
            }
            return true
        }
        return super.mouseClicked(mouseButtonEvent, doubled)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        if (dragging != Target.NONE) {
            dragging = Target.NONE
            subtitleCentered = cfg.subCenterX
            cfg.save()
            return true
        }
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        val dir = verticalAmount.sign.toInt()
        if (dir == 0) return false

        when (hovered()) {
            Target.VIDEO -> {
                val old = cfg.videoWidth
                val maxWidth = (mc.window.screenWidth * 2).coerceAtLeast(160)
                val new = (old + dir * 40).coerceIn(160, maxWidth)
                cfg.videoX += (old - new) / 2
                val oldH = (old / MpvPlayer.aspect()).toInt()
                val newH = (new / MpvPlayer.aspect()).toInt()
                cfg.videoY += (oldH - newH) / 2
                cfg.videoWidth = new
                clampAll()
                return true
            }
            Target.SUBS -> {
                if (MpvPlayer.usesDetachedImageSubtitles) {
                    cfg.imageSubScale = (cfg.imageSubScale + dir * 0.05f).coerceIn(0.35f, 3f)
                } else {
                    cfg.subScale = (cfg.subScale + dir * 0.2f).coerceIn(0.5f, 12f)
                }
                clampAll()
                return true
            }
            Target.NONE -> Unit
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private fun clampAll() {
        val w = mc.window.screenWidth
        val h = mc.window.screenHeight
        MpvHud.videoBox.let {
            it.x = cfg.videoX
            it.y = cfg.videoY
            it.width = cfg.videoWidth
            it.height = MpvHud.videoHeight()
            it.clampTo(w, h)
            cfg.videoX = it.x
            cfg.videoY = it.y
        }

        if (cfg.subAttached) return
        if (MpvPlayer.usesDetachedImageSubtitles) {
            // Keep the centre of the bitmap canvas reachable even when scaled > 1x.
            val centerX = (w / 2 + cfg.imageSubOffsetX).coerceIn(0, w)
            val centerY = (h / 2 + cfg.imageSubOffsetY).coerceIn(0, h)
            cfg.imageSubOffsetX = centerX - w / 2
            cfg.imageSubOffsetY = centerY - h / 2
        } else if (!MpvPlayer.usesImageSubtitles) {
            MpvHud.subBox.let {
                it.x = if (cfg.subCenterX) w / 2 - it.width / 2 else cfg.subX
                it.y = cfg.subY
                it.clampTo(w, h)
                if (!cfg.subCenterX) cfg.subX = it.x
                cfg.subY = it.y
            }
        }
    }

    override fun onClose() {
        dragging = Target.NONE
        subtitleCentered = false
        cfg.save()
        mc.setScreen(null)
    }

    override fun isPauseScreen(): Boolean = false
}
