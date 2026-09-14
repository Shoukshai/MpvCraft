package moe.rakka.mpvcraft.hud

import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.MpvCraft.mc
import moe.rakka.mpvcraft.mpv.MpvPlayer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.sign

/**
 * Mouse-only HUD layout editor.
 *
 * Closing this screen always returns directly to the game.  Detached subtitles
 * gain a single vertical centre guide/snap while being dragged; there is no
 * horizontal guide, so Y remains completely free.
 */
object MpvHudScreen : Screen(MpvUi.text("MpvCraft HUD layout")) {

    private enum class Target { NONE, VIDEO, SUBS }

    private const val CENTER_SNAP_PX = 14
    private val GUIDE = 0x9058C7FF.toInt()
    private val GUIDE_SNAPPED = 0xE077D69A.toInt()
    private val TEXT = 0xFFFFFFFF.toInt()
    private val MUTED = 0xFFB6C0CC.toInt()

    private var dragging = Target.NONE
    private var grabX = 0f
    private var grabY = 0f
    private var subtitleCentered = false
    private var doneButton: Button? = null

    private val cfg get() = MpvCraft.config

    override fun init() {
        subtitleCentered = cfg.subCenterX
        clampAll()
        doneButton = Button.builder(Component.empty()) { onClose() }
            .bounds(width - 72, 12, 60, 20)
            .build()
            .also { addRenderableWidget(it) }
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
                cfg.subX = (MpvHud.mouseX() + grabX).toInt()
                cfg.subY = (MpvHud.mouseY() + grabY).toInt()
                applySubtitleCenterSnap()
            }
            Target.NONE -> Unit
        }
        clampAll()

        // Preview below screen widgets so editor controls stay usable.
        MpvHud.draw(guiGraphics, editing = true)
        if (dragging == Target.SUBS && !cfg.subAttached) {
            drawSubtitleCenterGuide(guiGraphics)
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, deltaTicks)
        drawEditorChrome(guiGraphics)
        drawDoneLabel(guiGraphics)
    }

    private fun applySubtitleCenterSnap() {
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
            MpvPlayer.usesNativeImageSubtitles ->
                "Drag video to move - image subtitles are rendered inside the video"
            cfg.subAttached ->
                "Drag video to move - scroll to resize - subtitles are attached"
            else ->
                "Drag to move - scroll to resize - subtitle guide snaps X to centre"
        }
        val maxW = (width - 100).coerceAtLeast(160)
        val cardW = (MpvUi.width(note, size = 10) + 20).coerceAtMost(maxW)
        val x = 12
        val y = 12
        g.fill(x, y, x + cardW, y + 36, 0xCC11151B.toInt())
        g.fill(x, y, x + 2, y + 36, 0xFF58C7FF.toInt())
        MpvUi.draw(g, "HUD layout", x + 9, y + 6, TEXT, size = 12, bold = true)
        MpvUi.draw(g, MpvUi.clip(note, cardW - 18, size = 10), x + 9, y + 21, MUTED, size = 10)
    }

    private fun drawDoneLabel(g: GuiGraphicsExtractor) {
        val button = doneButton ?: return
        val lineH = MpvUi.lineHeight()
        MpvUi.drawCentered(
            g,
            "Done",
            button.getX() + button.getWidth() / 2,
            button.getY() + (button.getHeight() - lineH) / 2,
            TEXT,
        )
    }

    // ----------------------------------------------------------------

    private fun hovered(): Target {
        val mx = MpvHud.mouseX()
        val my = MpvHud.mouseY()
        if (cfg.subEnabled && !cfg.subAttached && !MpvPlayer.usesNativeImageSubtitles && MpvHud.subBox.contains(mx, my)) return Target.SUBS
        if (cfg.videoEnabled && MpvHud.videoBox.contains(mx, my)) return Target.VIDEO
        return Target.NONE
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(mouseButtonEvent, doubled)) return true

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
                    // If this cue was centre-anchored, start the drag from the
                    // *actual* rendered left edge rather than the stale saved X.
                    cfg.subX = MpvHud.subBox.x
                    cfg.subCenterX = false
                    subtitleCentered = false
                    grabX = cfg.subX - mx
                    grabY = cfg.subY - my
                }
                Target.NONE -> Unit
            }
            return true
        }
        return false
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
                val oldH = (old / moe.rakka.mpvcraft.mpv.MpvPlayer.aspect()).toInt()
                val newH = (new / moe.rakka.mpvcraft.mpv.MpvPlayer.aspect()).toInt()
                cfg.videoY += (oldH - newH) / 2
                cfg.videoWidth = new
                clampAll()
                return true
            }
            Target.SUBS -> {
                cfg.subScale = (cfg.subScale + dir * 0.2f).coerceIn(0.5f, 12f)
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
        if (!cfg.subAttached) {
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
        // /mpv hud is a standalone editor: Esc/Done goes straight back to play.
        mc.setScreen(null)
    }

    override fun isPauseScreen(): Boolean = false
}
