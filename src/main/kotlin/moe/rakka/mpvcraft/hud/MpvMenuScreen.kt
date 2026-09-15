package moe.rakka.mpvcraft.hud

import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.MpvCraft.mc
import moe.rakka.mpvcraft.mpv.MpvPlayer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Fully custom MpvCraft control surface.
 *
 * There are deliberately no vanilla Buttons/Sliders/Dropdowns on this screen.
 * Every card, button, toggle, slider, tab and list row is rendered and hit-tested
 * by MpvCraft so the UI cannot overlap, leak clicks, or inherit Minecraft widget
 * styling.  The layout follows the V5.4 visual mock-up: playback/display on the
 * left, track/chapter browser + media information on the right.
 */
object MpvMenuScreen : Screen(MpvUi.text("MpvCraft")) {

    private const val MAX_PANEL_W = 680
    private const val MAX_PANEL_H = 400
    private const val MIN_PANEL_W = 560
    private const val MIN_PANEL_H = 352
    private const val PANEL_MARGIN = 8
    private const val PAD = 13
    private const val COLUMN_GAP = 8
    private const val CARD_GAP = 8
    private const val HEADER_H = 54
    private const val FOOTER_H = 28
    private const val LIST_ROWS = 7
    private const val LIST_ROW_H = 18

    private const val PANEL_BG = 0xF0121922.toInt()
    private const val PANEL_EDGE = 0xCC2DA8F4.toInt()
    private const val PANEL_SHADOW = 0x54000000
    private const val CARD_BG = 0xD918222C.toInt()
    private const val CARD_BG_2 = 0xD91A252F.toInt()
    private const val CONTROL_BG = 0xD925333F.toInt()
    private const val CONTROL_HOVER = 0xEE314553.toInt()
    private const val CONTROL_DISABLED = 0xA51D2831.toInt()
    private const val BORDER = 0x80455A68.toInt()
    private const val DIVIDER = 0x663B4A55.toInt()
    private const val ACCENT = 0xFF2DA8F4.toInt()
    private const val ACCENT_SOFT = 0x5535A7F2
    private const val ACCENT_DARK = 0xDD174C70.toInt()
    private const val TEXT = 0xFFF1F5F9.toInt()
    private const val MUTED = 0xFFB1C0CE.toInt()
    private const val DIM = 0xFF8193A3.toInt()
    private const val DISABLED = 0xFF647482.toInt()
    private const val TRACK_OFF = 0xFF41515F.toInt()
    private const val TRACK_BG = 0xAA344651.toInt()
    private const val GOOD = 0xFF6FD29A.toInt()
    private const val WARN = 0xFFFFC873.toInt()
    private const val BAD = 0xFFFF8282.toInt()

    private enum class RightTab { SUBTITLES, AUDIO, CHAPTERS }
    private enum class ActionIcon { PAUSE, PLAY, REWIND, FORWARD, STOP, SKIP, FOLDER }
    private enum class MiniIcon { PLAYBACK, VOLUME, DISPLAY, SUBTITLES, AUDIO, CHAPTERS, INFO, LINK, HEART, CLOSE }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double): Boolean =
            px >= x && py >= y && px < x + w && py < y + h
    }

    private data class ListChoice(
        val id: Int?,
        val label: String,
        val selected: Boolean,
        val chapter: MpvPlayer.Chapter? = null,
    )

    private data class ToggleSpec(
        val rect: Rect,
        val title: String,
        val detail: String,
        val enabled: Boolean,
        val active: Boolean,
        val onClick: () -> Unit,
    )

    private val cfg get() = MpvCraft.config

    private var tracks: List<MpvPlayer.Track> = emptyList()
    private var chapters: List<MpvPlayer.Chapter> = emptyList()
    private var currentChapter = -1
    private var rightTab = RightTab.CHAPTERS
    private var listScroll = 0
    private var tickCounter = 0

    private var panelX = 0
    private var panelY = 0
    private var panelW = MAX_PANEL_W
    private var panelH = MAX_PANEL_H
    private var innerW = 0
    private var colW = 0
    private var leftX = 0
    private var rightX = 0
    private var bodyTop = 0
    private var bodyBottom = 0
    private var bodyH = 0
    private var rightTopH = 0
    private var rightInfoH = 0

    private var draggingPanel = false
    private var panelGrabX = 0
    private var panelGrabY = 0
    private var draggingVolume = false

    private var observedHasFile = false
    private var observedSubtitlePresentation = MpvPlayer.SubtitlePresentation.NONE
    @Volatile private var filePickerBusy = false
    private var filePickerError: String? = null
    private var notice: String? = null
    private var lastOpenDirectory: String? = null

    override fun init() {
        panelW = ((width - 24).coerceAtMost(MAX_PANEL_W)).coerceAtLeast(minOf(MIN_PANEL_W, width - 8))
        panelH = ((height - 24).coerceAtMost(MAX_PANEL_H)).coerceAtLeast(minOf(MIN_PANEL_H, height - 8))

        if (cfg.menuPositionSet) {
            panelX = clampPanelX(cfg.menuX)
            panelY = clampPanelY(cfg.menuY)
        } else {
            panelX = (width - panelW) / 2
            panelY = ((height - panelH) / 2).coerceAtLeast(PANEL_MARGIN)
        }

        calculateLayout()
        refreshMediaLists()
        observedHasFile = MpvPlayer.hasFile
        MpvPlayer.syncSubtitlePresentation(cfg.subEnabled, cfg.subAttached)
        observedSubtitlePresentation = MpvPlayer.subtitlePresentation
    }

    override fun tick() {
        super.tick()
        tickCounter++
        MpvPlayer.syncSubtitlePresentation(cfg.subEnabled, cfg.subAttached)

        val now = MpvPlayer.hasFile
        val presentation = MpvPlayer.subtitlePresentation
        if (now != observedHasFile || presentation != observedSubtitlePresentation) {
            observedHasFile = now
            observedSubtitlePresentation = presentation
            refreshMediaLists()
        }

        if (now && tickCounter % 10 == 0) {
            currentChapter = MpvPlayer.currentChapterIndex()
            // Audio/sub selection can be changed from commands while the menu is open.
            tracks = MpvPlayer.tracks()
        }
    }

    private fun refreshMediaLists() {
        if (MpvPlayer.available && MpvPlayer.hasFile) {
            tracks = MpvPlayer.tracks()
            chapters = MpvPlayer.chapters()
            currentChapter = MpvPlayer.currentChapterIndex()
        } else {
            tracks = emptyList()
            chapters = emptyList()
            currentChapter = -1
        }
        listScroll = listScroll.coerceIn(0, maxListScroll())
    }

    private fun calculateLayout() {
        innerW = panelW - PAD * 2
        colW = (innerW - COLUMN_GAP) / 2
        leftX = panelX + PAD
        rightX = leftX + colW + COLUMN_GAP
        bodyTop = panelY + HEADER_H
        bodyBottom = panelY + panelH - FOOTER_H
        bodyH = bodyBottom - bodyTop - 5
        rightTopH = (bodyH * 69 / 100).coerceAtLeast(190)
        rightInfoH = (bodyH - CARD_GAP - rightTopH).coerceAtLeast(72)
    }

    // ---------------------------------------------------------------------
    // Geometry

    private fun leftCard(): Rect = Rect(leftX, bodyTop, colW, bodyH)
    private fun browserCard(): Rect = Rect(rightX, bodyTop, colW, rightTopH)
    private fun infoCard(): Rect = Rect(rightX, bodyTop + rightTopH + CARD_GAP, colW, rightInfoH)
    private fun closeRect(): Rect = Rect(panelX + panelW - 42, panelY + 12, 26, 26)
    private fun headerPlayRect(): Rect = Rect(panelX + 17, panelY + 11, 36, 36)
    private fun headerDragRect(): Rect = Rect(panelX, panelY, panelW - 50, HEADER_H)

    private fun playbackItemRects(): List<Rect> {
        val card = leftCard()
        val x = card.x + 12
        val y = card.y + 31
        val available = card.w - 24
        val gap = 5
        val itemW = (available - gap * 5) / 6
        return (0 until 6).map { i -> Rect(x + i * (itemW + gap), y, itemW, 53) }
    }

    private fun volumeTrackRect(): Rect {
        val card = leftCard()
        return Rect(card.x + 14, card.y + 128, card.w - 28, 9)
    }

    private fun toggleSpecs(): List<ToggleSpec> {
        val card = leftCard()
        val startY = card.y + 191
        val gap = 5
        val half = (card.w - 24 - gap) / 2
        val h = 37
        val x1 = card.x + 12
        val x2 = x1 + half + gap
        val imageSub = MpvPlayer.usesImageSubtitles
        val detachedDetail = if (imageSub) "Separate PGS/image layer" else "Separate subtitle HUD"
        return listOf(
            ToggleSpec(Rect(x1, startY, half, h), "Video", "Show video in game", cfg.videoEnabled, true) {
                cfg.videoEnabled = !cfg.videoEnabled
            },
            ToggleSpec(Rect(x2, startY, half, h), "Subtitles", "Show subtitles", cfg.subEnabled, true) {
                cfg.subEnabled = !cfg.subEnabled
                MpvPlayer.setSubtitlesEnabled(cfg.subEnabled, cfg.subAttached)
            },
            ToggleSpec(Rect(x1, startY + h + gap, half, h), "Detached Subs", detachedDetail, !cfg.subAttached, true) {
                cfg.subAttached = !cfg.subAttached
                MpvPlayer.syncSubtitlePresentation(cfg.subEnabled, cfg.subAttached, force = true)
            },
            ToggleSpec(Rect(x2, startY + h + gap, half, h), "Subtitle Background", "Show subtitle background", cfg.subBackground, !imageSub) {
                cfg.subBackground = !cfg.subBackground
            },
            ToggleSpec(Rect(x1, startY + (h + gap) * 2, half, h), "Flip Image", "Flip video vertically", cfg.flipY, true) {
                cfg.flipY = !cfg.flipY
                MpvPlayer.flipY = cfg.flipY
            },
        )
    }

    private fun tabRects(): Map<RightTab, Rect> {
        val card = browserCard()
        val x = card.x + 9
        val y = card.y + 8
        val w = card.w - 18
        val each = w / 3
        return mapOf(
            RightTab.SUBTITLES to Rect(x, y, each, 28),
            RightTab.AUDIO to Rect(x + each, y, each, 28),
            RightTab.CHAPTERS to Rect(x + each * 2, y, w - each * 2, 28),
        )
    }

    private fun listRect(): Rect {
        val card = browserCard()
        return Rect(card.x + 9, card.y + 42, card.w - 18, LIST_ROWS * LIST_ROW_H)
    }

    private fun browserSkipRect(): Rect {
        val card = browserCard()
        return Rect(card.x + card.w - 88, card.y + card.h - 28, 76, 19)
    }

    private fun infoHudRowRect(): Rect {
        val card = infoCard()
        return Rect(card.x + 10, card.y + 52, card.w / 2 - 16, 14)
    }

    private fun footerLeftRect(): Rect = Rect(panelX + 14, panelY + panelH - 25, panelW / 2 + 90, 19)

    // ---------------------------------------------------------------------
    // Rendering

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)

        fillRoundRect(graphics, panelX - 4, panelY + 5, panelW + 8, panelH + 7, 20, PANEL_SHADOW)
        outlineRoundRect(graphics, panelX, panelY, panelW, panelH, 18, PANEL_EDGE, PANEL_BG)

        drawCardShell(graphics, leftCard())
        drawCardShell(graphics, browserCard())
        drawCardShell(graphics, infoCard())
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        deltaTicks: Float,
    ) {
        if (draggingPanel) movePanelTo(mouseX - panelGrabX, mouseY - panelGrabY)
        if (draggingVolume) updateVolumeFromMouse(mouseX.toDouble())

        super.extractRenderState(graphics, mouseX, mouseY, deltaTicks)

        drawHeader(graphics, mouseX, mouseY)
        drawLeftCard(graphics, mouseX, mouseY)
        drawBrowserCard(graphics, mouseX, mouseY)
        drawInfoCard(graphics, mouseX, mouseY)
        drawFooter(graphics, mouseX, mouseY)
    }

    private fun drawHeader(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val playBadge = headerPlayRect()
        fillRoundRect(g, playBadge.x, playBadge.y, playBadge.w, playBadge.h, playBadge.w / 2, 0xCC304756.toInt())
        drawActionGlyph(g, if (MpvPlayer.paused) ActionIcon.PLAY else ActionIcon.PAUSE, playBadge, TEXT)

        MpvUi.draw(g, "MpvCraft", panelX + 63, panelY + 11, TEXT, size = 18, bold = true)

        val state = when {
            filePickerBusy -> "Opening"
            MpvPlayer.hasFile && MpvPlayer.paused -> "Paused"
            MpvPlayer.hasFile -> "Playing"
            else -> "Ready"
        }
        val stateColor = when {
            filePickerError != null -> BAD
            filePickerBusy -> WARN
            MpvPlayer.hasFile -> ACCENT
            else -> MUTED
        }
        val stateX = panelX + 63
        MpvUi.draw(g, state, stateX, panelY + 32, stateColor, size = 11)
        val stateW = MpvUi.width(state, size = 11)
        val media = when {
            filePickerError != null -> filePickerError!!
            notice != null -> notice!!
            MpvPlayer.hasFile -> MpvPlayer.title.ifBlank { "media" }
            else -> "No media loaded"
        }
        MpvUi.draw(g, "•", stateX + stateW + 5, panelY + 32, MUTED, size = 11)
        MpvUi.draw(
            g,
            MpvUi.clip(media, (panelW - 250 - stateW).coerceAtLeast(110), size = 11),
            stateX + stateW + 17,
            panelY + 32,
            MUTED,
            size = 11,
        )

        val close = closeRect()
        val closeHovered = close.contains(mouseX.toDouble(), mouseY.toDouble())
        fillRoundRect(g, close.x, close.y, close.w, close.h, 10, if (closeHovered) CONTROL_HOVER else CONTROL_BG)
        outlineRoundRect(g, close.x, close.y, close.w, close.h, 10, BORDER, if (closeHovered) CONTROL_HOVER else CONTROL_BG)
        drawMiniIcon(g, MiniIcon.CLOSE, close.x + 7, close.y + 7, 12, if (closeHovered) TEXT else MUTED)

        val hint = "Drag to move"
        MpvUi.draw(
            g,
            hint,
            close.x - 18 - MpvUi.width(hint, size = 10),
            panelY + 20,
            DIM,
            size = 10,
        )

        // subtle handle above the title, as in the mock-up
        fillRoundRect(g, panelX + panelW / 2 - 19, panelY + 7, 38, 3, 2, 0xFF668093.toInt())
    }

    private fun drawLeftCard(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val card = leftCard()

        drawSectionTitle(g, card.x + 13, card.y + 12, MiniIcon.PLAYBACK, "Playback")
        val actions = playbackItemRects()
        val specs = listOf(
            Triple(if (MpvPlayer.paused) ActionIcon.PLAY else ActionIcon.PAUSE, if (MpvPlayer.paused) "Play" else "Pause", MpvPlayer.hasFile),
            Triple(ActionIcon.REWIND, "- 10s", MpvPlayer.hasFile),
            Triple(ActionIcon.FORWARD, "+ 10s", MpvPlayer.hasFile),
            Triple(ActionIcon.STOP, "Stop", MpvPlayer.hasFile),
            Triple(ActionIcon.SKIP, "Skip OP", MpvPlayer.introTarget(chapters) != null),
            Triple(ActionIcon.FOLDER, "Open File", !filePickerBusy),
        )
        actions.zip(specs).forEachIndexed { index, (rect, spec) ->
            drawActionButton(
                g,
                rect,
                spec.first,
                spec.second,
                spec.third,
                rect.contains(mouseX.toDouble(), mouseY.toDouble()),
                primary = index == 0,
            )
        }

        drawDivider(g, card.x + 14, card.y + 101, card.w - 28)

        drawSectionTitle(g, card.x + 13, card.y + 111, MiniIcon.VOLUME, "Volume")
        val volumeValue = cfg.volume
        MpvUi.draw(g, "${volumeValue.coerceIn(0, 200)}%", card.x + card.w - 14 - MpvUi.width("200%", size = 10), card.y + 113, MUTED, size = 10)
        drawVolume(g, volumeTrackRect(), mouseX, mouseY)
        MpvUi.draw(g, "0%", card.x + 14, card.y + 143, DIM, size = 9)
        val maxLabel = "200%"
        MpvUi.draw(g, maxLabel, card.x + card.w - 14 - MpvUi.width(maxLabel, size = 9), card.y + 143, DIM, size = 9)

        drawDivider(g, card.x + 14, card.y + 165, card.w - 28)
        drawSectionTitle(g, card.x + 13, card.y + 174, MiniIcon.DISPLAY, "Display")

        toggleSpecs().forEach { spec -> drawToggleCard(g, spec, mouseX, mouseY) }
    }

    private fun drawActionButton(
        g: GuiGraphicsExtractor,
        rect: Rect,
        icon: ActionIcon,
        label: String,
        active: Boolean,
        hovered: Boolean,
        primary: Boolean,
    ) {
        val boxSize = minOf(39, rect.w)
        val bx = rect.x + (rect.w - boxSize) / 2
        val by = rect.y
        val bg = when {
            !active -> CONTROL_DISABLED
            hovered -> CONTROL_HOVER
            primary -> ACCENT_DARK
            else -> CONTROL_BG
        }
        val edge = when {
            !active -> DIVIDER
            primary -> ACCENT
            hovered -> 0xFF4B6577.toInt()
            else -> BORDER
        }
        outlineRoundRect(g, bx, by, boxSize, 38, 11, edge, bg)
        drawActionGlyph(g, icon, Rect(bx, by, boxSize, 38), if (active) TEXT else DISABLED)
        MpvUi.drawCentered(
            g,
            label,
            rect.x + rect.w / 2,
            rect.y + 42,
            when {
                !active -> DISABLED
                primary -> ACCENT
                else -> MUTED
            },
            size = 9,
        )
    }

    private fun drawVolume(g: GuiGraphicsExtractor, rect: Rect, mouseX: Int, mouseY: Int) {
        val hovered = rect.contains(mouseX.toDouble(), mouseY.toDouble())
        val value = cfg.volume.coerceIn(0, 200)
        val centerY = rect.y + rect.h / 2
        fillRoundRect(g, rect.x, centerY - 3, rect.w, 6, 3, TRACK_BG)
        val fillW = (rect.w * value / 200.0).roundToInt().coerceIn(0, rect.w)
        if (fillW > 0) fillRoundRect(g, rect.x, centerY - 3, fillW, 6, 3, ACCENT)
        val knobX = (rect.x + fillW).coerceIn(rect.x + 5, rect.x + rect.w - 5)
        fillRoundRect(g, knobX - 6, centerY - 6, 12, 12, 6, if (hovered || draggingVolume) 0xFFFFFFFF.toInt() else 0xFFE9F2F8.toInt())
    }

    private fun drawToggleCard(g: GuiGraphicsExtractor, spec: ToggleSpec, mouseX: Int, mouseY: Int) {
        val hovered = spec.active && spec.rect.contains(mouseX.toDouble(), mouseY.toDouble())
        outlineRoundRect(
            g,
            spec.rect.x,
            spec.rect.y,
            spec.rect.w,
            spec.rect.h,
            10,
            if (hovered) 0xAA5D7585.toInt() else BORDER,
            if (hovered) CONTROL_HOVER else CONTROL_BG,
        )
        val color = if (spec.active) TEXT else DISABLED
        val detail = if (spec.active) DIM else DISABLED
        MpvUi.draw(g, spec.title, spec.rect.x + 8, spec.rect.y + 6, color, size = 10, bold = true)
        MpvUi.draw(
            g,
            MpvUi.clip(spec.detail, (spec.rect.w - 48).coerceAtLeast(45), size = 8),
            spec.rect.x + 8,
            spec.rect.y + 21,
            detail,
            size = 8,
        )
        drawSwitch(g, spec.rect.x + spec.rect.w - 34, spec.rect.y + 11, spec.enabled, spec.active)
    }

    private fun drawSwitch(g: GuiGraphicsExtractor, x: Int, y: Int, enabled: Boolean, active: Boolean) {
        val track = when {
            !active -> 0xFF34414C.toInt()
            enabled -> ACCENT
            else -> TRACK_OFF
        }
        fillRoundRect(g, x, y, 27, 15, 8, track)
        val knobX = if (enabled) x + 14 else x + 2
        fillRoundRect(g, knobX, y + 2, 11, 11, 6, if (active) 0xFFF3F7FA.toInt() else 0xFFA7B0B8.toInt())
    }

    private fun drawBrowserCard(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val card = browserCard()
        val tabs = tabRects()
        tabs.forEach { (tab, rect) ->
            val selected = tab == rightTab
            val hovered = rect.contains(mouseX.toDouble(), mouseY.toDouble())
            if (hovered && !selected) fillRoundRect(g, rect.x + 2, rect.y + 2, rect.w - 4, rect.h - 5, 8, 0x55374B59)
            val icon = when (tab) {
                RightTab.SUBTITLES -> MiniIcon.SUBTITLES
                RightTab.AUDIO -> MiniIcon.AUDIO
                RightTab.CHAPTERS -> MiniIcon.CHAPTERS
            }
            val label = when (tab) {
                RightTab.SUBTITLES -> "Subtitles"
                RightTab.AUDIO -> "Audio"
                RightTab.CHAPTERS -> "Chapters"
            }
            val color = if (selected) ACCENT else MUTED
            val contentW = 14 + 5 + MpvUi.width(label, size = 10)
            val startX = rect.x + (rect.w - contentW) / 2
            drawMiniIcon(g, icon, startX, rect.y + 8, 12, color)
            MpvUi.draw(g, label, startX + 18, rect.y + 8, color, size = 10)
            if (selected) fillRoundRect(g, rect.x + 8, rect.y + rect.h - 2, rect.w - 16, 2, 1, ACCENT)
        }
        drawDivider(g, card.x + 9, card.y + 37, card.w - 18)

        drawBrowserList(g, mouseX, mouseY)

        val count = chapters.size
        val current = if (currentChapter >= 0 && count > 0) "${currentChapter + 1} / $count" else "-- / $count"
        MpvUi.draw(g, "Current chapter", card.x + 10, card.y + card.h - 23, MUTED, size = 9)
        MpvUi.draw(g, current, card.x + 81, card.y + card.h - 23, ACCENT, size = 10, bold = true)

        val skip = browserSkipRect()
        val skipActive = MpvPlayer.introTarget(chapters) != null
        val skipHovered = skipActive && skip.contains(mouseX.toDouble(), mouseY.toDouble())
        outlineRoundRect(
            g,
            skip.x,
            skip.y,
            skip.w,
            skip.h,
            5,
            if (skipHovered) 0xFF5B7283.toInt() else BORDER,
            when {
                !skipActive -> CONTROL_DISABLED
                skipHovered -> CONTROL_HOVER
                else -> CONTROL_BG
            },
        )
        drawActionGlyph(g, ActionIcon.SKIP, Rect(skip.x + 6, skip.y + 2, 16, 15), if (skipActive) TEXT else DISABLED)
        MpvUi.draw(g, "Skip intro", skip.x + 25, skip.y + 5, if (skipActive) TEXT else DISABLED, size = 8)
    }

    private fun drawBrowserList(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rect = listRect()
        outlineRoundRect(g, rect.x, rect.y, rect.w, rect.h, 10, BORDER, CARD_BG_2)

        val all = listChoices()
        val visible = visibleChoices()
        if (visible.isEmpty()) {
            val empty = when (rightTab) {
                RightTab.SUBTITLES -> "No subtitle tracks"
                RightTab.AUDIO -> "No audio tracks"
                RightTab.CHAPTERS -> "No chapters"
            }
            MpvUi.drawCentered(g, empty, rect.x + rect.w / 2, rect.y + rect.h / 2 - 5, DIM, size = 9)
            return
        }

        visible.forEachIndexed { row, choice ->
            val y = rect.y + row * LIST_ROW_H
            val rowRect = Rect(rect.x + 1, y + 1, rect.w - 2, LIST_ROW_H - 1)
            val hovered = rowRect.contains(mouseX.toDouble(), mouseY.toDouble())
            if (choice.selected) {
                fillRoundRect(g, rowRect.x, rowRect.y, rowRect.w, rowRect.h, 6, ACCENT_SOFT)
                outlineRoundRect(g, rowRect.x, rowRect.y, rowRect.w, rowRect.h, 6, ACCENT, ACCENT_SOFT)
            } else if (hovered) {
                fillRoundRect(g, rowRect.x, rowRect.y, rowRect.w, rowRect.h, 6, 0x55374B59)
            }
            if (row > 0 && !choice.selected) g.fill(rect.x + 5, y, rect.x + rect.w - 6, y + 1, DIVIDER)

            if (rightTab == RightTab.CHAPTERS && choice.chapter != null) {
                val chapter = choice.chapter
                if (choice.selected) drawSmallTriangle(g, rect.x + 9, y + 6, 6, ACCENT)
                val numberColor = if (choice.selected) TEXT else MUTED
                MpvUi.drawCentered(g, (chapter.index + 1).toString(), rect.x + 31, y + 5, numberColor, size = 9)
                MpvUi.draw(g, formatTime(chapter.time), rect.x + 51, y + 5, numberColor, size = 9)
                MpvUi.draw(
                    g,
                    MpvUi.clip(chapter.title.ifBlank { "Chapter ${chapter.index + 1}" }, rect.w - 151, size = 9),
                    rect.x + 126,
                    y + 5,
                    if (choice.selected) TEXT else MUTED,
                    size = 9,
                )
            } else {
                val marker = if (choice.selected) "•" else ""
                if (marker.isNotEmpty()) MpvUi.draw(g, marker, rect.x + 10, y + 5, ACCENT, size = 9, bold = true)
                MpvUi.draw(
                    g,
                    MpvUi.clip(choice.label, rect.w - 35, size = 9),
                    rect.x + 25,
                    y + 5,
                    if (choice.selected) TEXT else MUTED,
                    size = 9,
                )
            }
        }

        if (all.size > LIST_ROWS) {
            val trackX = rect.x + rect.w - 5
            val usable = rect.h - 8
            fillRoundRect(g, trackX, rect.y + 4, 2, usable, 1, 0xFF30404C.toInt())
            val maxStart = all.size - LIST_ROWS
            val thumbH = (usable * LIST_ROWS / all.size).coerceAtLeast(10)
            val travel = (usable - thumbH).coerceAtLeast(0)
            val thumbY = rect.y + 4 + if (maxStart == 0) 0 else travel * listScroll / maxStart
            fillRoundRect(g, trackX - 1, thumbY, 4, thumbH, 2, ACCENT)
        }
    }

    private fun drawInfoCard(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val card = infoCard()
        drawSectionTitle(g, card.x + 11, card.y + 9, MiniIcon.INFO, "Media Information")

        val dividerX = card.x + card.w / 2
        g.fill(dividerX, card.y + 27, dividerX + 1, card.y + card.h - 10, DIVIDER)

        val source = if (MpvPlayer.videoWidth > 0 && MpvPlayer.videoHeight > 0) {
            "${MpvPlayer.videoWidth} x ${MpvPlayer.videoHeight}"
        } else "--"
        val render = if (MpvPlayer.targetWidth > 0 && MpvPlayer.targetHeight > 0) {
            "${MpvPlayer.targetWidth} x ${MpvPlayer.targetHeight}"
        } else "--"
        val hud = "${cfg.videoWidth} x ${MpvHud.videoHeight()} px"
        val subMode = when {
            !cfg.subEnabled -> "Off"
            MpvPlayer.usesDetachedImageSubtitles -> "Detached image"
            MpvPlayer.usesNativeImageSubtitles && !cfg.subAttached -> "Image fallback"
            MpvPlayer.usesNativeImageSubtitles -> "Native image"
            cfg.subAttached -> "Attached text"
            else -> "Detached text"
        }

        val leftLabelX = card.x + 13
        val leftValueX = card.x + 82
        val baseY = card.y + 30
        drawInfoRow(g, leftLabelX, leftValueX, baseY, "Source", source)
        drawInfoRow(g, leftLabelX, leftValueX, baseY + 14, "Render", render)
        val hudHover = infoHudRowRect().contains(mouseX.toDouble(), mouseY.toDouble())
        drawInfoRow(g, leftLabelX, leftValueX, baseY + 28, "HUD", hud, valueColor = if (hudHover) ACCENT else MUTED)
        if (card.h >= 88) drawInfoRow(g, leftLabelX, leftValueX, baseY + 42, "Subtitle mode", subMode)

        val selectedAudio = tracks.firstOrNull { it.type == "audio" && it.selected }
        val selectedSub = tracks.firstOrNull { it.type == "sub" && it.selected }
        val rightLabelX = dividerX + 13
        val rightValueX = dividerX + 92
        drawInfoRow(g, rightLabelX, rightValueX, baseY, "Audio language", trackLanguage(selectedAudio))
        drawInfoRow(g, rightLabelX, rightValueX, baseY + 22, "Subtitle language", trackLanguage(selectedSub))
    }

    private fun drawInfoRow(
        g: GuiGraphicsExtractor,
        labelX: Int,
        valueX: Int,
        y: Int,
        label: String,
        value: String,
        valueColor: Int = MUTED,
    ) {
        MpvUi.draw(g, label, labelX, y, DIM, size = 8)
        MpvUi.draw(g, MpvUi.clip(value, 88, size = 8), valueX, y, valueColor, size = 8)
    }

    private fun drawFooter(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val y = panelY + panelH - 19
        drawMiniIcon(g, MiniIcon.LINK, panelX + 17, y - 1, 12, DIM)
        val footerText = when {
            filePickerBusy -> "Opening local file picker..."
            filePickerError != null -> filePickerError!!
            notice != null -> notice!!
            else -> "Local files, URLs, chapters and subtitle tracks in one place."
        }
        val footerColor = when {
            filePickerError != null -> BAD
            filePickerBusy -> WARN
            notice != null -> GOOD
            else -> DIM
        }
        MpvUi.draw(
            g,
            MpvUi.clip(footerText, panelW - 250, size = 8),
            panelX + 35,
            y,
            footerColor,
            size = 8,
        )

        val right = "Enjoy your viewing!"
        val rightX = panelX + panelW - 18 - MpvUi.width(right, size = 8)
        drawMiniIcon(g, MiniIcon.HEART, rightX - 17, y - 1, 11, DIM)
        MpvUi.draw(g, right, rightX, y, DIM, size = 8)
    }

    private fun drawCardShell(g: GuiGraphicsExtractor, rect: Rect) {
        outlineRoundRect(g, rect.x, rect.y, rect.w, rect.h, 13, BORDER, CARD_BG)
    }

    private fun drawSectionTitle(g: GuiGraphicsExtractor, x: Int, y: Int, icon: MiniIcon, label: String) {
        drawMiniIcon(g, icon, x, y + 1, 12, MUTED)
        MpvUi.draw(g, label, x + 18, y, TEXT, size = 11, bold = true)
    }

    private fun drawDivider(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int) {
        g.fill(x, y, x + w, y + 1, DIVIDER)
    }

    // ---------------------------------------------------------------------
    // Browser / media helpers

    private fun listChoices(): List<ListChoice> = when (rightTab) {
        RightTab.SUBTITLES -> {
            val subs = tracks.filter { it.type == "sub" }
            listOf(ListChoice(null, "None", subs.none { it.selected })) +
                subs.map { ListChoice(it.id, it.label(), it.selected) }
        }
        RightTab.AUDIO -> tracks.filter { it.type == "audio" }
            .map { ListChoice(it.id, it.label(), it.selected) }
        RightTab.CHAPTERS -> chapters.map { chapter ->
            ListChoice(chapter.index, chapter.title.ifBlank { "Chapter ${chapter.index + 1}" }, chapter.index == currentChapter, chapter)
        }
    }

    private fun maxListScroll(): Int = (listChoices().size - LIST_ROWS).coerceAtLeast(0)

    private fun visibleChoices(): List<ListChoice> {
        listScroll = listScroll.coerceIn(0, maxListScroll())
        return listChoices().drop(listScroll).take(LIST_ROWS)
    }

    private fun choose(item: ListChoice) {
        when (rightTab) {
            RightTab.SUBTITLES -> {
                MpvPlayer.selectTrack("sub", item.id)
                MpvPlayer.syncSubtitlePresentation(cfg.subEnabled, cfg.subAttached, force = true)
                tracks = if (MpvPlayer.hasFile) MpvPlayer.tracks() else emptyList()
            }
            RightTab.AUDIO -> {
                MpvPlayer.selectTrack("audio", item.id)
                tracks = if (MpvPlayer.hasFile) MpvPlayer.tracks() else emptyList()
            }
            RightTab.CHAPTERS -> item.id?.let {
                MpvPlayer.selectChapter(it)
                currentChapter = it
            }
        }
    }

    private fun trackLanguage(track: MpvPlayer.Track?): String {
        if (track == null) return "--"
        return when {
            track.title.isNotBlank() && track.lang.isNotBlank() -> "${track.title} (${track.lang})"
            track.lang.isNotBlank() -> track.lang
            track.title.isNotBlank() -> track.title
            else -> track.codec.ifBlank { "track ${track.id}" }
        }
    }

    private fun formatTime(time: Double): String {
        val total = time.coerceAtLeast(0.0).toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun skipIntro() {
        val target = MpvPlayer.skipIntro()
        if (target != null) {
            currentChapter = target.index
            notice = "Skipped to ${target.title.ifBlank { "chapter ${target.index + 1}" }}"
        } else {
            notice = "No intro target ahead"
        }
    }

    // ---------------------------------------------------------------------
    // Input

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = mouseButtonEvent.x()
        val my = mouseButtonEvent.y()
        if (mouseButtonEvent.button() != 0) return super.mouseClicked(mouseButtonEvent, doubled)

        if (closeRect().contains(mx, my)) {
            onClose()
            return true
        }

        if (headerPlayRect().contains(mx, my) && MpvPlayer.hasFile) {
            MpvPlayer.togglePause()
            return true
        }

        playbackItemRects().forEachIndexed { index, rect ->
            if (!rect.contains(mx, my)) return@forEachIndexed
            when (index) {
                0 -> if (MpvPlayer.hasFile) MpvPlayer.togglePause()
                1 -> if (MpvPlayer.hasFile) MpvPlayer.seek(-10)
                2 -> if (MpvPlayer.hasFile) MpvPlayer.seek(10)
                3 -> if (MpvPlayer.hasFile) {
                    MpvPlayer.stop()
                    refreshMediaLists()
                }
                4 -> if (MpvPlayer.introTarget(chapters) != null) skipIntro()
                5 -> if (!filePickerBusy) openLocalFile()
            }
            return true
        }

        val volume = volumeTrackRect()
        val expandedVolume = Rect(volume.x - 3, volume.y - 7, volume.w + 6, volume.h + 14)
        if (expandedVolume.contains(mx, my)) {
            draggingVolume = true
            updateVolumeFromMouse(mx)
            return true
        }

        toggleSpecs().forEach { spec ->
            if (spec.active && spec.rect.contains(mx, my)) {
                notice = null
                spec.onClick()
                return true
            }
        }

        tabRects().forEach { (tab, rect) ->
            if (rect.contains(mx, my)) {
                rightTab = tab
                listScroll = 0
                return true
            }
        }

        val list = listRect()
        if (list.contains(mx, my)) {
            val row = ((my - list.y) / LIST_ROW_H).toInt()
            visibleChoices().getOrNull(row)?.let(::choose)
            return true
        }

        val skip = browserSkipRect()
        if (skip.contains(mx, my) && MpvPlayer.introTarget(chapters) != null) {
            skipIntro()
            return true
        }

        // Preserve the old HUD editor without adding a button that is absent from
        // the visual mock-up: the "HUD" information row itself is the edit affordance.
        if (infoHudRowRect().contains(mx, my)) {
            cfg.save()
            mc.setScreen(MpvHudScreen)
            return true
        }

        if (headerDragRect().contains(mx, my)) {
            draggingPanel = true
            panelGrabX = (mx - panelX).toInt()
            panelGrabY = (my - panelY).toInt()
            return true
        }

        return super.mouseClicked(mouseButtonEvent, doubled)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        var consumed = false
        if (draggingPanel) {
            draggingPanel = false
            cfg.save()
            consumed = true
        }
        if (draggingVolume) {
            draggingVolume = false
            cfg.save()
            consumed = true
        }
        return if (consumed) true else super.mouseReleased(mouseButtonEvent)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        if (listRect().contains(mouseX, mouseY)) {
            val maxStart = maxListScroll()
            if (maxStart > 0) {
                val step = -verticalAmount.sign.toInt()
                if (step != 0) listScroll = (listScroll + step).coerceIn(0, maxStart)
            }
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private fun updateVolumeFromMouse(mouseX: Double) {
        val rect = volumeTrackRect()
        val ratio = ((mouseX - rect.x) / rect.w.toDouble()).coerceIn(0.0, 1.0)
        val next = (ratio * 200.0).roundToInt().coerceIn(0, 200)
        if (cfg.volume != next) {
            cfg.volume = next
            MpvPlayer.setVolume(next)
        }
    }

    // ---------------------------------------------------------------------
    // Native local file picker

    private fun openLocalFile() {
        if (filePickerBusy) return
        filePickerError = null
        notice = null
        filePickerBusy = true

        val initial = lastOpenDirectory?.let(::File) ?: mc.gameDirectory
        Thread({
            val result = runCatching { NativeFilePicker.chooseMedia(initial) }
            mc.execute {
                filePickerBusy = false
                result.onSuccess { selected ->
                    if (selected != null) {
                        lastOpenDirectory = selected.parentFile?.absolutePath
                        MpvPlayer.load(selected.absolutePath)
                        MpvPlayer.setVolume(cfg.volume)
                        observedHasFile = false
                        notice = "Opening ${selected.name}"
                    }
                }.onFailure { t ->
                    MpvCraft.logger.error("Could not open the media file picker", t)
                    filePickerError = "File picker unavailable: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }, "MpvCraft-file-picker").apply {
            isDaemon = true
            start()
        }
    }

    // ---------------------------------------------------------------------
    // Panel movement

    private fun movePanelTo(requestedX: Int, requestedY: Int) {
        val nextX = clampPanelX(requestedX)
        val nextY = clampPanelY(requestedY)
        if (nextX == panelX && nextY == panelY) return
        panelX = nextX
        panelY = nextY
        cfg.menuPositionSet = true
        cfg.menuX = panelX
        cfg.menuY = panelY
        calculateLayout()
    }

    private fun clampPanelX(x: Int): Int {
        val max = (width - panelW - PANEL_MARGIN).coerceAtLeast(PANEL_MARGIN)
        return x.coerceIn(PANEL_MARGIN, max)
    }

    private fun clampPanelY(y: Int): Int {
        val max = (height - panelH - PANEL_MARGIN).coerceAtLeast(PANEL_MARGIN)
        return y.coerceIn(PANEL_MARGIN, max)
    }

    override fun onClose() {
        draggingPanel = false
        draggingVolume = false
        cfg.save()
        mc.setScreen(null)
    }

    override fun isPauseScreen(): Boolean = false

    // ---------------------------------------------------------------------
    // Primitive drawing helpers. Keeping the menu self-rendered means it behaves
    // identically regardless of the vanilla widget theme/resource pack.

    private fun fillRoundRect(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Int,
        color: Int,
    ) {
        MpvUi.roundedRect(g, x, y, w, h, radius, color)
    }

    private fun outlineRoundRect(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Int,
        border: Int,
        fill: Int,
    ) {
        MpvUi.roundedRect(g, x, y, w, h, radius, fill, border, 1f)
    }

    private fun drawSmallTriangle(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: Int) {
        MpvUi.icon(g, MpvUi.Icon.PLAY, x, y, size, color)
    }

    private fun drawActionGlyph(g: GuiGraphicsExtractor, icon: ActionIcon, rect: Rect, color: Int) {
        val vector = when (icon) {
            ActionIcon.PAUSE -> MpvUi.Icon.PAUSE
            ActionIcon.PLAY -> MpvUi.Icon.PLAY
            ActionIcon.REWIND -> MpvUi.Icon.REWIND_10
            ActionIcon.FORWARD -> MpvUi.Icon.FORWARD_10
            ActionIcon.STOP -> MpvUi.Icon.STOP
            ActionIcon.SKIP -> MpvUi.Icon.SKIP
            ActionIcon.FOLDER -> MpvUi.Icon.FOLDER
        }
        val iconSize = when (icon) {
            ActionIcon.REWIND, ActionIcon.FORWARD -> minOf(rect.w, rect.h) - 11
            else -> minOf(rect.w, rect.h) - 13
        }.coerceAtLeast(10)
        MpvUi.icon(
            g,
            vector,
            rect.x + (rect.w - iconSize) / 2,
            rect.y + (rect.h - iconSize) / 2,
            iconSize,
            color,
        )
    }

    private fun drawMiniIcon(g: GuiGraphicsExtractor, icon: MiniIcon, x: Int, y: Int, size: Int, color: Int) {
        val vector = when (icon) {
            MiniIcon.PLAYBACK -> MpvUi.Icon.PLAY
            MiniIcon.VOLUME -> MpvUi.Icon.VOLUME
            MiniIcon.DISPLAY -> MpvUi.Icon.DISPLAY
            MiniIcon.SUBTITLES -> MpvUi.Icon.SUBTITLES
            MiniIcon.AUDIO -> MpvUi.Icon.AUDIO
            MiniIcon.CHAPTERS -> MpvUi.Icon.CHAPTERS
            MiniIcon.INFO -> MpvUi.Icon.INFO
            MiniIcon.LINK -> MpvUi.Icon.LINK
            MiniIcon.HEART -> MpvUi.Icon.HEART
            MiniIcon.CLOSE -> MpvUi.Icon.CLOSE
        }
        MpvUi.icon(g, vector, x, y, size, color)
    }
}
