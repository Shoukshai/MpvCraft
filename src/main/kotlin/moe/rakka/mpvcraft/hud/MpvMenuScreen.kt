package moe.rakka.mpvcraft.hud

import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.MpvCraft.mc
import moe.rakka.mpvcraft.mpv.MpvBitmapSubtitlePlayer
import moe.rakka.mpvcraft.mpv.MpvPlayer
import moe.rakka.mpvcraft.mpv.MpvPlaylist
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

object MpvMenuScreen : Screen(MpvUi.text("MpvCraft")) {
    private const val MAX_PANEL_W = 680
    private const val MAX_PANEL_H = 400
    private const val MIN_PANEL_W = 560
    private const val MIN_PANEL_H = 352
    private const val PANEL_MARGIN = 8
    private const val PAD = 13
    private const val COLUMN_GAP = 8
    private const val HEADER_H = 54
    private const val FOOTER_H = 28
    private const val LIST_ROW_H = 18
    private const val LEFT_CONTENT_H = 640
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

    private enum class RightTab { SUBTITLES, AUDIO, CHAPTERS, PLAYLIST }
    private enum class ActionIcon { PREVIOUS, PAUSE, PLAY, REWIND, FORWARD, NEXT, STOP, SKIP }
    private enum class MiniIcon { PLAYBACK, VOLUME, DISPLAY, SUBTITLES, AUDIO, CHAPTERS, PLAYLIST, INFO, LINK, HEART, MOVE, CLOSE, FOLDER, FILE }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double): Boolean = px >= x && py >= y && px < x + w && py < y + h
        val bottom: Int get() = y + h
        val right: Int get() = x + w
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
    private val rightScrolls = RightTab.entries.associateWith { 0 }.toMutableMap()
    private var leftScroll = 0
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

    private var draggingPanel = false
    private var panelGrabX = 0
    private var panelGrabY = 0
    private var draggingVolume = false
    private var draggingVideoOpacity = false
    private var draggingTimeline = false
    private var lastTimelineSeek = Double.NaN
    private var lastTimelineSeekNanos = 0L

    private var infoDrawerOpen = false
    private var infoDrawerProgress = 0f
    private var openMediaChoice = false

    private var observedHasFile = false
    private var observedSubtitlePresentation = MpvPlayer.SubtitlePresentation.NONE
    private var observedPlaylistIndex = -1
    private var observedChapterIndex = -1
    @Volatile private var filePickerBusy = false
    private var filePickerError: String? = null
    private var notice: String? = null
    private var lastOpenDirectory: String? = null

    override fun init() {
        draggingPanel = false
        draggingVolume = false
        draggingVideoOpacity = false
        draggingTimeline = false
        lastTimelineSeek = Double.NaN
        lastTimelineSeekNanos = 0L
        openMediaChoice = false
        infoDrawerOpen = false
        infoDrawerProgress = 0f
        if (!filePickerBusy) {
            filePickerError = null
            notice = null
        }
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
        observedPlaylistIndex = MpvPlaylist.currentIndex
        observedChapterIndex = currentChapter
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
            tracks = MpvPlayer.tracks()
        }
        if (MpvPlaylist.currentIndex != observedPlaylistIndex) {
            observedPlaylistIndex = MpvPlaylist.currentIndex
            if (rightTab == RightTab.PLAYLIST) ensureSelectedVisible(RightTab.PLAYLIST)
        }
        if (currentChapter != observedChapterIndex) {
            observedChapterIndex = currentChapter
            if (rightTab == RightTab.CHAPTERS) ensureSelectedVisible(RightTab.CHAPTERS)
        }
        infoDrawerProgress = if (infoDrawerOpen) 1f else 0f
        clampScrolls()
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
        clampScrolls()
    }

    private fun calculateLayout() {
        innerW = panelW - PAD * 2
        colW = (innerW - COLUMN_GAP) / 2
        leftX = panelX + PAD
        rightX = leftX + colW + COLUMN_GAP
        bodyTop = panelY + HEADER_H
        bodyBottom = panelY + panelH - FOOTER_H
        bodyH = bodyBottom - bodyTop - 5
    }

    private fun leftCard(): Rect = Rect(leftX, bodyTop, colW, bodyH)
    private fun browserCard(): Rect = Rect(rightX, bodyTop, colW, bodyH)
    private fun closeRect(): Rect = Rect(panelX + panelW - 42, panelY + 12, 26, 26)
    private fun moveUiRect(): Rect = Rect(closeRect().x - 84, panelY + 13, 74, 24)
    private fun headerDragRect(): Rect = Rect(panelX, panelY, panelW - 140, HEADER_H)
    private fun leftY(offset: Int): Int = leftCard().y + 10 + offset - leftScroll
    private fun leftVisible(rect: Rect): Boolean {
        val card = leftCard()
        return rect.bottom > card.y + 4 && rect.y < card.bottom - 4
    }
    private fun leftHit(rect: Rect, px: Double, py: Double): Boolean =
        leftCard().contains(px, py) && leftVisible(rect) && rect.contains(px, py)

    private fun playbackItemRects(): List<Rect> {
        val card = leftCard()
        val x = card.x + 12
        val y = leftY(24)
        val available = card.w - 24
        val gap = 3
        val itemW = (available - gap * 6) / 7
        return (0 until 7).map { i -> Rect(x + i * (itemW + gap), y, itemW, 49) }
    }

    private fun timelineTrackRect(): Rect = Rect(leftCard().x + 16, leftY(84), leftCard().w - 32, 8)
    private fun mediaButtonRects(): Pair<Rect, Rect> {
        val card = leftCard()
        val gap = 6
        val half = (card.w - 28 - gap) / 2
        val y = leftY(148)
        return Rect(card.x + 14, y, half, 29) to Rect(card.x + 14 + half + gap, y, half, 29)
    }
    private fun volumeTrackRect(): Rect = Rect(leftCard().x + 14, leftY(229), leftCard().w - 28, 9)

    private fun videoToggleSpecs(): List<ToggleSpec> {
        val card = leftCard()
        val gap = 5
        val half = (card.w - 24 - gap) / 2
        val y = leftY(300)
        return listOf(
            ToggleSpec(Rect(card.x + 12, y, half, 37), "Video", "Show video in game", cfg.videoEnabled, true) {
                cfg.videoEnabled = !cfg.videoEnabled
            },
            ToggleSpec(Rect(card.x + 12 + half + gap, y, half, 37), "Flip Image", "Flip video vertically", cfg.flipY, true) {
                cfg.flipY = !cfg.flipY
                MpvPlayer.flipY = cfg.flipY
            },
        )
    }

    private fun videoOpacityCardRect(): Rect = Rect(leftCard().x + 12, leftY(342), leftCard().w - 24, 37)
    private fun videoOpacityTrackRect(): Rect {
        val card = videoOpacityCardRect()
        return Rect(card.x + 8, card.y + 24, card.w - 16, 7)
    }

    private fun subtitleToggleSpecs(): List<ToggleSpec> {
        val card = leftCard()
        val gap = 5
        val half = (card.w - 24 - gap) / 2
        val y = leftY(424)
        val imageSub = MpvPlayer.usesImageSubtitles
        val bitmapFailure = MpvBitmapSubtitlePlayer.failureReason
        val detachedDetail = when {
            !imageSub -> "Separate subtitle HUD"
            cfg.subAttached -> "Separate PGS/image layer"
            MpvPlayer.usesDetachedImageSubtitles && bitmapFailure == null -> "PGS/image layer active"
            bitmapFailure != null -> "PGS fallback - toggle to retry"
            else -> "Preparing PGS/image layer"
        }
        return listOf(
            ToggleSpec(Rect(card.x + 12, y, half, 37), "Subtitles", "Show subtitles", cfg.subEnabled, true) {
                cfg.subEnabled = !cfg.subEnabled
                MpvPlayer.setSubtitlesEnabled(cfg.subEnabled, cfg.subAttached)
            },
            ToggleSpec(Rect(card.x + 12 + half + gap, y, half, 37), "Detached Subs", detachedDetail, !cfg.subAttached, true) {
                cfg.subAttached = !cfg.subAttached
                MpvPlayer.syncSubtitlePresentation(cfg.subEnabled, cfg.subAttached, force = true)
            },
            ToggleSpec(Rect(card.x + 12, y + 42, card.w - 24, 37), "Subtitle Background", "Show subtitle background", cfg.subBackground, !imageSub) {
                cfg.subBackground = !cfg.subBackground
            },
        )
    }

    private fun playlistSortRect(): Rect = Rect(leftCard().x + 12, leftY(550), (leftCard().w - 29) / 2, 29)
    private fun playlistDirectionRect(): Rect {
        val first = playlistSortRect()
        return Rect(first.right + 5, first.y, leftCard().right - 12 - (first.right + 5), 29)
    }
    private fun playlistAutoRect(): Rect = Rect(leftCard().x + 12, leftY(584), leftCard().w - 24, 37)

    private fun tabRects(): Map<RightTab, Rect> {
        val card = browserCard()
        val x = card.x + 9
        val y = card.y + 8
        val w = card.w - 18
        val each = w / 4
        return mapOf(
            RightTab.SUBTITLES to Rect(x, y, each, 28),
            RightTab.AUDIO to Rect(x + each, y, each, 28),
            RightTab.CHAPTERS to Rect(x + each * 2, y, each, 28),
            RightTab.PLAYLIST to Rect(x + each * 3, y, w - each * 3, 28),
        )
    }

    private fun listRect(): Rect {
        val card = browserCard()
        return Rect(card.x + 9, card.y + 42, card.w - 18, (card.h - 76).coerceAtLeast(LIST_ROW_H * 4))
    }

    private fun browserBottomActionRect(): Rect {
        val card = browserCard()
        return Rect(card.x + card.w - 88, card.y + card.h - 28, 76, 19)
    }

    private fun infoDrawerWidth(): Int = (colW * 72 / 100).coerceIn(180, 230)
    private fun infoHandleRect(): Rect = Rect(panelX + panelW + 8, bodyTop + 12, 18, 48)
    private fun infoDrawerRect(): Rect {
        val handle = infoHandleRect()
        val openX = handle.right + 8
        val closedX = openX
        val x = if (infoDrawerProgress > 0.5f) openX else closedX
        return Rect(x, bodyTop + 7, infoDrawerWidth(), bodyH - 14)
    }
    private fun infoDrawerHudRect(): Rect {
        val d = infoDrawerRect()
        return Rect(d.x + 12, d.y + 78, d.w - 24, 16)
    }

    private fun footerLeftRect(): Rect = Rect(panelX + 14, panelY + panelH - 25, panelW / 2 + 90, 19)

    private fun openMediaModalRect(): Rect = Rect(panelX + panelW / 2 - 105, panelY + panelH / 2 - 48, 210, 96)
    private fun openMediaModalButtons(): Pair<Rect, Rect> {
        val m = openMediaModalRect()
        val gap = 8
        val w = (m.w - 28 - gap) / 2
        return Rect(m.x + 14, m.y + 49, w, 30) to Rect(m.x + 14 + w + gap, m.y + 49, w, 30)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        fillRoundRect(graphics, panelX - 4, panelY + 5, panelW + 8, panelH + 7, 20, PANEL_SHADOW)
        outlineRoundRect(graphics, panelX, panelY, panelW, panelH, 18, PANEL_EDGE, PANEL_BG)
        drawCardShell(graphics, leftCard())
        drawCardShell(graphics, browserCard())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        if (draggingPanel) movePanelTo(mouseX - panelGrabX, mouseY - panelGrabY)
        if (draggingVolume) updateVolumeFromMouse(mouseX.toDouble())
        if (draggingVideoOpacity) updateVideoOpacityFromMouse(mouseX.toDouble())
        if (draggingTimeline) updateTimelineFromMouse(mouseX.toDouble())
        super.extractRenderState(graphics, mouseX, mouseY, deltaTicks)
        drawHeader(graphics, mouseX, mouseY)
        drawLeftCard(graphics, mouseX, mouseY)
        drawBrowserCard(graphics, mouseX, mouseY)
        drawFooter(graphics)
        drawInfoDrawer(graphics, mouseX, mouseY)
        if (openMediaChoice) drawOpenMediaModal(graphics, mouseX, mouseY)
    }

    private fun drawHeader(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        MpvUi.draw(g, "MpvCraft", panelX + 18, panelY + 11, TEXT, size = 18, bold = true)
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
        val stateX = panelX + 18
        MpvUi.draw(g, state, stateX, panelY + 32, stateColor, size = 11)
        val stateW = MpvUi.width(state, size = 11)
        val media = when {
            filePickerError != null -> filePickerError!!
            notice != null -> notice!!
            MpvPlayer.hasFile -> MpvPlayer.title.ifBlank { "media" }
            else -> "No media loaded"
        }
        MpvUi.draw(g, "•", stateX + stateW + 5, panelY + 32, MUTED, size = 11)
        MpvUi.draw(g, MpvUi.clip(media, (panelW - 330 - stateW).coerceAtLeast(86), size = 11), stateX + stateW + 17, panelY + 32, MUTED, size = 11)

        val close = closeRect()
        val closeHovered = close.contains(mouseX.toDouble(), mouseY.toDouble())
        fillRoundRect(g, close.x, close.y, close.w, close.h, 10, if (closeHovered) CONTROL_HOVER else CONTROL_BG)
        outlineRoundRect(g, close.x, close.y, close.w, close.h, 10, BORDER, if (closeHovered) CONTROL_HOVER else CONTROL_BG)
        drawMiniIcon(g, MiniIcon.CLOSE, close.x + 7, close.y + 7, 12, if (closeHovered) TEXT else MUTED)

        val move = moveUiRect()
        val moveHovered = move.contains(mouseX.toDouble(), mouseY.toDouble())
        val moveFill = if (moveHovered) CONTROL_HOVER else 0xB91D2A34.toInt()
        fillRoundRect(g, move.x, move.y, move.w, move.h, 12, moveFill)
        outlineRoundRect(g, move.x, move.y, move.w, move.h, 12, if (moveHovered) ACCENT_SOFT else BORDER, moveFill)
        drawMiniIcon(g, MiniIcon.MOVE, move.x + 9, move.y + 6, 12, if (moveHovered) ACCENT else MUTED)
        MpvUi.draw(g, "Move UI", move.x + 25, move.y + 7, if (moveHovered) TEXT else MUTED, size = 10)
        fillRoundRect(g, panelX + panelW / 2 - 19, panelY + 7, 38, 3, 2, 0xFF668093.toInt())
    }

    private fun drawLeftCard(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val card = leftCard()
        g.enableScissor(card.x + 1, card.y + 1, card.right - 1, card.bottom - 1)
        try {
            drawLeftPlayback(g, mouseX, mouseY)
            drawLeftMedia(g, mouseX, mouseY)
            drawLeftVolume(g, mouseX, mouseY)
            drawLeftVideo(g, mouseX, mouseY)
            drawLeftSubtitles(g, mouseX, mouseY)
            drawLeftPlaylist(g, mouseX, mouseY)
        } finally {
            g.disableScissor()
        }
        drawLeftScrollbar(g)
    }

    private fun drawLeftPlayback(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val titleY = leftY(0)
        if (titleY in leftCard().y + 5..leftCard().bottom - 20) drawSectionTitle(g, leftCard().x + 13, titleY, MiniIcon.PLAYBACK, "Playback")
        val actions = playbackItemRects()
        val specs = listOf(
            Triple(ActionIcon.PREVIOUS, "Prev", MpvPlaylist.currentIndex > 0),
            Triple(ActionIcon.REWIND, "-5s", MpvPlayer.hasFile),
            Triple(if (MpvPlayer.paused) ActionIcon.PLAY else ActionIcon.PAUSE, if (MpvPlayer.paused) "Play" else "Pause", MpvPlayer.hasFile),
            Triple(ActionIcon.FORWARD, "+5s", MpvPlayer.hasFile),
            Triple(ActionIcon.NEXT, "Next", MpvPlaylist.currentIndex in 0 until MpvPlaylist.entries.lastIndex),
            Triple(ActionIcon.STOP, "Stop", MpvPlayer.hasFile),
            Triple(ActionIcon.SKIP, "Skip OP", MpvPlayer.introTarget(chapters) != null),
        )
        actions.zip(specs).forEachIndexed { index, (rect, spec) ->
            if (leftVisible(rect)) drawActionButton(g, rect, spec.first, spec.second, spec.third, rect.contains(mouseX.toDouble(), mouseY.toDouble()), primary = index == 2)
        }
        drawTimeline(g, mouseX, mouseY)
    }

    private fun drawTimeline(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rect = timelineTrackRect()
        if (!leftVisible(Rect(rect.x, rect.y - 4, rect.w, 31))) return
        val duration = MpvPlayer.durationSeconds
        val position = MpvPlayer.positionSeconds.coerceAtLeast(0.0)
        val ratio = if (duration > 0.0) (position / duration).coerceIn(0.0, 1.0) else 0.0
        val centerY = rect.y + rect.h / 2
        fillRoundRect(g, rect.x, centerY - 2, rect.w, 5, 3, TRACK_BG)
        chapters.forEach { chapter ->
            if (duration <= 0.0) return@forEach
            val x = rect.x + (rect.w * (chapter.time / duration).coerceIn(0.0, 1.0)).roundToInt()
            g.fill(x, centerY - 5, x + 1, centerY + 6, 0xAA8DA7B8.toInt())
        }
        val fillW = (rect.w * ratio).roundToInt().coerceIn(0, rect.w)
        if (fillW > 0) fillRoundRect(g, rect.x, centerY - 2, fillW, 5, 3, ACCENT)
        val knobX = (rect.x + fillW).coerceIn(rect.x + 4, rect.right - 4)
        val hovered = Rect(rect.x, rect.y - 5, rect.w, rect.h + 10).contains(mouseX.toDouble(), mouseY.toDouble())
        fillRoundRect(g, knobX - 5, centerY - 5, 10, 10, 5, if (hovered || draggingTimeline) 0xFFFFFFFF.toInt() else 0xFFE9F2F8.toInt())
        MpvUi.draw(g, formatTimeShort(position), rect.x, rect.y + 13, DIM, size = 8)
        val durationLabel = if (duration > 0.0) formatTimeShort(duration) else "--:--"
        MpvUi.draw(g, durationLabel, rect.right - MpvUi.width(durationLabel, size = 8), rect.y + 13, DIM, size = 8)
    }

    private fun drawLeftMedia(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val titleY = leftY(126)
        if (titleY in leftCard().y + 5..leftCard().bottom - 20) drawSectionTitle(g, leftCard().x + 13, titleY, MiniIcon.FOLDER, "Open")
        val (open, subs) = mediaButtonRects()
        if (leftVisible(open)) drawPillButton(g, open, MiniIcon.FOLDER, "Open Media", !filePickerBusy, open.contains(mouseX.toDouble(), mouseY.toDouble()))
        if (leftVisible(subs)) drawPillButton(g, subs, MiniIcon.SUBTITLES, "Add Subtitle", MpvPlayer.hasFile && !filePickerBusy, subs.contains(mouseX.toDouble(), mouseY.toDouble()))
    }

    private fun drawLeftVolume(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val titleY = leftY(202)
        if (titleY in leftCard().y + 5..leftCard().bottom - 20) {
            drawSectionTitle(g, leftCard().x + 13, titleY, MiniIcon.VOLUME, "Volume")
            val value = "${cfg.volume.coerceIn(0, 200)}%"
            MpvUi.draw(g, value, leftCard().right - 14 - MpvUi.width(value, size = 10), titleY + 2, MUTED, size = 10)
        }
        val rect = volumeTrackRect()
        if (!leftVisible(Rect(rect.x, rect.y - 2, rect.w, 30))) return
        drawVolume(g, rect, mouseX, mouseY)
        MpvUi.draw(g, "0%", rect.x, rect.y + 15, DIM, size = 8)
        MpvUi.draw(g, "200%", rect.right - MpvUi.width("200%", size = 8), rect.y + 15, DIM, size = 8)
    }

    private fun drawLeftVideo(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val titleY = leftY(276)
        if (titleY in leftCard().y + 5..leftCard().bottom - 20) drawSectionTitle(g, leftCard().x + 13, titleY, MiniIcon.DISPLAY, "Video")
        videoToggleSpecs().forEach { if (leftVisible(it.rect)) drawToggleCard(g, it, mouseX, mouseY) }
        val opacity = videoOpacityCardRect()
        if (leftVisible(opacity)) drawVideoOpacityCard(g, mouseX, mouseY)
    }

    private fun drawLeftSubtitles(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val titleY = leftY(400)
        if (titleY in leftCard().y + 5..leftCard().bottom - 20) drawSectionTitle(g, leftCard().x + 13, titleY, MiniIcon.SUBTITLES, "Subtitles")
        subtitleToggleSpecs().forEach { if (leftVisible(it.rect)) drawToggleCard(g, it, mouseX, mouseY) }
    }

    private fun drawLeftPlaylist(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val titleY = leftY(526)
        if (titleY in leftCard().y + 5..leftCard().bottom - 20) {
            drawSectionTitle(g, leftCard().x + 13, titleY, MiniIcon.CHAPTERS, "Playlist")
            val count = if (MpvPlaylist.active) "${MpvPlaylist.entries.size} items" else "No playlist"
            MpvUi.draw(g, count, leftCard().right - 14 - MpvUi.width(count, size = 8), titleY + 2, DIM, size = 8)
        }
        val sort = playlistSortRect()
        val dir = playlistDirectionRect()
        if (leftVisible(sort)) {
            val label = if (MpvPlaylist.sortMode == MpvPlaylist.SortMode.NAME) "Sort: Name" else "Sort: Date"
            drawTextButton(g, sort, label, sort.contains(mouseX.toDouble(), mouseY.toDouble()))
        }
        if (leftVisible(dir)) {
            val label = if (MpvPlaylist.sortMode == MpvPlaylist.SortMode.NAME) {
                if (MpvPlaylist.ascending) "A -> Z" else "Z -> A"
            } else {
                if (MpvPlaylist.ascending) "Old -> New" else "New -> Old"
            }
            drawTextButton(g, dir, label, dir.contains(mouseX.toDouble(), mouseY.toDouble()))
        }
        val auto = playlistAutoRect()
        if (leftVisible(auto)) {
            drawToggleCard(g, ToggleSpec(auto, "Auto-play next", "Continue through the folder", MpvPlaylist.autoPlayNext, true) {
                MpvPlaylist.setAutoPlayNext(!MpvPlaylist.autoPlayNext)
            }, mouseX, mouseY)
        }
    }

    private fun drawLeftScrollbar(g: GuiGraphicsExtractor) {
        val card = leftCard()
        val max = maxLeftScroll()
        if (max <= 0) return
        val trackY = card.y + 8
        val trackH = card.h - 16
        val trackX = card.right - 5
        fillRoundRect(g, trackX, trackY, 2, trackH, 1, 0xFF30404C.toInt())
        val viewport = (card.h - 20).coerceAtLeast(1)
        val thumbH = (trackH * viewport / LEFT_CONTENT_H).coerceAtLeast(18)
        val travel = (trackH - thumbH).coerceAtLeast(0)
        val thumbY = trackY + if (max == 0) 0 else travel * leftScroll / max
        fillRoundRect(g, trackX - 1, thumbY, 4, thumbH, 2, ACCENT)
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
                RightTab.PLAYLIST -> MiniIcon.PLAYLIST
            }
            val label = when (tab) {
                RightTab.SUBTITLES -> "Subs"
                RightTab.AUDIO -> "Audio"
                RightTab.CHAPTERS -> "Chapters"
                RightTab.PLAYLIST -> "Playlist"
            }
            val color = if (selected) ACCENT else MUTED
            val contentW = 12 + 4 + MpvUi.width(label, size = 9)
            val startX = rect.x + (rect.w - contentW) / 2
            drawMiniIcon(g, icon, startX, rect.y + 8, 11, color)
            MpvUi.draw(g, label, startX + 15, rect.y + 8, color, size = 9)
            if (selected) fillRoundRect(g, rect.x + 7, rect.y + rect.h - 2, rect.w - 14, 2, 1, ACCENT)
        }
        drawDivider(g, card.x + 9, card.y + 37, card.w - 18)
        drawBrowserList(g, mouseX, mouseY)
        drawBrowserStatus(g, mouseX, mouseY)
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
                RightTab.PLAYLIST -> "Open a folder to create a playlist"
            }
            MpvUi.drawCentered(g, empty, rect.x + rect.w / 2, rect.y + rect.h / 2 - 5, DIM, size = 9)
            return
        }

        visible.forEachIndexed { row, choice ->
            val y = rect.y + row * LIST_ROW_H
            if (y + LIST_ROW_H > rect.bottom) return@forEachIndexed
            val rowRect = Rect(rect.x + 1, y + 1, rect.w - 2, LIST_ROW_H - 1)
            val hovered = rowRect.contains(mouseX.toDouble(), mouseY.toDouble())
            if (choice.selected) {
                fillRoundRect(g, rowRect.x, rowRect.y, rowRect.w, rowRect.h, 6, ACCENT_SOFT)
                outlineRoundRect(g, rowRect.x, rowRect.y, rowRect.w, rowRect.h, 6, ACCENT, ACCENT_SOFT)
            } else if (hovered) fillRoundRect(g, rowRect.x, rowRect.y, rowRect.w, rowRect.h, 6, 0x55374B59)
            if (row > 0 && !choice.selected) g.fill(rect.x + 5, y, rect.right - 6, y + 1, DIVIDER)

            when {
                rightTab == RightTab.CHAPTERS && choice.chapter != null -> {
                    val chapter = choice.chapter
                    if (choice.selected) drawSmallTriangle(g, rect.x + 9, y + 6, 6, ACCENT)
                    val c = if (choice.selected) TEXT else MUTED
                    MpvUi.drawCentered(g, (chapter.index + 1).toString(), rect.x + 31, y + 5, c, size = 9)
                    MpvUi.draw(g, formatTime(chapter.time), rect.x + 51, y + 5, c, size = 9)
                    MpvUi.draw(g, MpvUi.clip(chapter.title.ifBlank { "Chapter ${chapter.index + 1}" }, rect.w - 151, size = 9), rect.x + 126, y + 5, c, size = 9)
                }
                rightTab == RightTab.PLAYLIST -> {
                    val marker = if (choice.selected) "•" else ""
                    if (marker.isNotEmpty()) MpvUi.draw(g, marker, rect.x + 9, y + 5, ACCENT, size = 9, bold = true)
                    val number = choice.id?.plus(1)?.toString()?.padStart(2, '0').orEmpty()
                    MpvUi.draw(g, number, rect.x + 23, y + 5, if (choice.selected) TEXT else DIM, size = 8)
                    MpvUi.draw(g, MpvUi.clip(choice.label, rect.w - 58, size = 9), rect.x + 47, y + 5, if (choice.selected) TEXT else MUTED, size = 9)
                }
                else -> {
                    if (choice.selected) MpvUi.draw(g, "•", rect.x + 10, y + 5, ACCENT, size = 9, bold = true)
                    MpvUi.draw(g, MpvUi.clip(choice.label, rect.w - 35, size = 9), rect.x + 25, y + 5, if (choice.selected) TEXT else MUTED, size = 9)
                }
            }
        }

        val rows = visibleRowCount()
        if (all.size > rows) {
            val trackX = rect.right - 5
            val usable = rect.h - 8
            fillRoundRect(g, trackX, rect.y + 4, 2, usable, 1, 0xFF30404C.toInt())
            val maxStart = (all.size - rows).coerceAtLeast(0)
            val thumbH = (usable * rows / all.size).coerceAtLeast(10)
            val travel = (usable - thumbH).coerceAtLeast(0)
            val scroll = rightScroll()
            val thumbY = rect.y + 4 + if (maxStart == 0) 0 else travel * scroll / maxStart
            fillRoundRect(g, trackX - 1, thumbY, 4, thumbH, 2, ACCENT)
        }
    }

    private fun drawBrowserStatus(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val card = browserCard()
        val y = card.bottom - 23
        val text = when (rightTab) {
            RightTab.SUBTITLES -> "${tracks.count { it.type == "sub" }} subtitle tracks"
            RightTab.AUDIO -> "${tracks.count { it.type == "audio" }} audio tracks"
            RightTab.CHAPTERS -> {
                val count = chapters.size
                if (currentChapter >= 0 && count > 0) "Current chapter  ${currentChapter + 1} / $count" else "Current chapter  -- / $count"
            }
            RightTab.PLAYLIST -> {
                val count = MpvPlaylist.entries.size
                if (MpvPlaylist.currentIndex >= 0 && count > 0) "Current item  ${MpvPlaylist.currentIndex + 1} / $count" else "Current item  -- / $count"
            }
        }
        MpvUi.draw(g, text, card.x + 10, y, MUTED, size = 9)
        if (rightTab == RightTab.CHAPTERS) {
            val skip = browserBottomActionRect()
            val active = MpvPlayer.introTarget(chapters) != null
            drawSmallActionButton(g, skip, "Skip intro", active, active && skip.contains(mouseX.toDouble(), mouseY.toDouble()))
        } else if (rightTab == RightTab.PLAYLIST) {
            val next = browserBottomActionRect()
            val active = MpvPlaylist.currentIndex in 0 until MpvPlaylist.entries.lastIndex
            drawSmallActionButton(g, next, "Next", active, active && next.contains(mouseX.toDouble(), mouseY.toDouble()))
        }
    }

    private fun drawInfoDrawer(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val handle = infoHandleRect()
        val handleHover = handle.contains(mouseX.toDouble(), mouseY.toDouble())
        fillRoundRect(g, handle.x, handle.y, handle.w, handle.h, 8, if (handleHover) CONTROL_HOVER else CONTROL_BG)
        outlineRoundRect(g, handle.x, handle.y, handle.w, handle.h, 8, if (handleHover) ACCENT else BORDER, if (handleHover) CONTROL_HOVER else CONTROL_BG)
        drawMiniIcon(g, MiniIcon.INFO, handle.x + 3, handle.y + 18, 12, if (handleHover || infoDrawerOpen) ACCENT else MUTED)
        if (infoDrawerProgress <= 0.02f) return

        val card = infoDrawerRect()
        outlineRoundRect(g, card.x, card.y, card.w, card.h, 13, BORDER, 0xF51A252F.toInt())
        drawSectionTitle(g, card.x + 12, card.y + 11, MiniIcon.INFO, "Media Information")
        val source = if (MpvPlayer.videoWidth > 0 && MpvPlayer.videoHeight > 0) "${MpvPlayer.videoWidth} x ${MpvPlayer.videoHeight}" else "--"
        val render = if (MpvPlayer.targetWidth > 0 && MpvPlayer.targetHeight > 0) "${MpvPlayer.targetWidth} x ${MpvPlayer.targetHeight}" else "--"
        val hud = "${cfg.videoWidth} x ${MpvHud.videoHeight()} px"
        val subMode = when {
            !cfg.subEnabled -> "Off"
            MpvPlayer.usesDetachedImageSubtitles -> "Detached image"
            MpvPlayer.usesNativeImageSubtitles && !cfg.subAttached -> "Image fallback"
            MpvPlayer.usesNativeImageSubtitles -> "Native image"
            cfg.subAttached -> "Attached text"
            else -> "Detached text"
        }
        val selectedAudio = tracks.firstOrNull { it.type == "audio" && it.selected }
        val selectedSub = tracks.firstOrNull { it.type == "sub" && it.selected }
        val labelX = card.x + 13
        val valueX = card.x + 92
        var y = card.y + 37
        drawInfoRow(g, labelX, valueX, y, "Source", source, card.w - 105); y += 18
        drawInfoRow(g, labelX, valueX, y, "Render", render, card.w - 105); y += 18
        val hudHover = infoDrawerHudRect().contains(mouseX.toDouble(), mouseY.toDouble())
        drawInfoRow(g, labelX, valueX, y, "HUD", hud, card.w - 105, if (hudHover) ACCENT else MUTED); y += 18
        drawInfoRow(g, labelX, valueX, y, "Subtitle mode", subMode, card.w - 105); y += 25
        drawDivider(g, card.x + 12, y, card.w - 24); y += 12
        drawInfoRow(g, labelX, valueX, y, "Audio", trackLanguage(selectedAudio), card.w - 105); y += 20
        drawInfoRow(g, labelX, valueX, y, "Subtitles", trackLanguage(selectedSub), card.w - 105); y += 25
        if (MpvPlaylist.active) {
            drawDivider(g, card.x + 12, y, card.w - 24); y += 12
            drawInfoRow(g, labelX, valueX, y, "Playlist", "${MpvPlaylist.currentIndex + 1} / ${MpvPlaylist.entries.size}", card.w - 105); y += 18
            drawInfoRow(g, labelX, valueX, y, "Sort", if (MpvPlaylist.sortMode == MpvPlaylist.SortMode.NAME) "Name" else "Date modified", card.w - 105)
        }
    }

    private fun drawInfoRow(g: GuiGraphicsExtractor, labelX: Int, valueX: Int, y: Int, label: String, value: String, maxWidth: Int, valueColor: Int = MUTED) {
        MpvUi.draw(g, label, labelX, y, DIM, size = 8)
        MpvUi.draw(g, MpvUi.clip(value, maxWidth.coerceAtLeast(40), size = 8), valueX, y, valueColor, size = 8)
    }

    private fun drawOpenMediaModal(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        g.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + panelH - 1, 0x88000000.toInt())
        val modal = openMediaModalRect()
        outlineRoundRect(g, modal.x, modal.y, modal.w, modal.h, 14, ACCENT_SOFT, 0xF51A252F.toInt())
        MpvUi.drawCentered(g, "Open Media", modal.x + modal.w / 2, modal.y + 14, TEXT, size = 12, bold = true)
        MpvUi.drawCentered(g, "Choose what you want to open", modal.x + modal.w / 2, modal.y + 31, DIM, size = 8)
        val (file, folder) = openMediaModalButtons()
        drawPillButton(g, file, MiniIcon.FILE, "File", !filePickerBusy, file.contains(mouseX.toDouble(), mouseY.toDouble()))
        drawPillButton(g, folder, MiniIcon.FOLDER, "Folder", !filePickerBusy, folder.contains(mouseX.toDouble(), mouseY.toDouble()))
    }

    private fun drawFooter(g: GuiGraphicsExtractor) {
        val y = panelY + panelH - 19
        drawMiniIcon(g, MiniIcon.LINK, panelX + 17, y - 1, 12, DIM)
        val bitmapFailure = if (!cfg.subAttached && MpvPlayer.usesNativeImageSubtitles) MpvBitmapSubtitlePlayer.failureReason else null
        val footerText = when {
            filePickerBusy -> "Opening native picker..."
            filePickerError != null -> filePickerError!!
            bitmapFailure != null -> "PGS detach fallback: $bitmapFailure"
            notice != null -> notice!!
            MpvPlaylist.active -> "Playlist: ${MpvPlaylist.entries.size} items from ${MpvPlaylist.sourceDirectory?.name ?: "folder"}"
            else -> "Local files, folders, URLs, chapters and subtitle tracks in one place."
        }
        val footerColor = when {
            filePickerError != null -> BAD
            filePickerBusy -> WARN
            bitmapFailure != null -> WARN
            notice != null -> GOOD
            else -> DIM
        }
        MpvUi.draw(g, MpvUi.clip(footerText, panelW - 250, size = 8), panelX + 35, y, footerColor, size = 8)
        val right = "Enjoy your viewing!"
        val rx = panelX + panelW - 18 - MpvUi.width(right, size = 8)
        drawMiniIcon(g, MiniIcon.HEART, rx - 17, y - 1, 11, DIM)
        MpvUi.draw(g, right, rx, y, DIM, size = 8)
    }

    private fun drawActionButton(g: GuiGraphicsExtractor, rect: Rect, icon: ActionIcon, label: String, active: Boolean, hovered: Boolean, primary: Boolean) {
        val boxSize = minOf(34, rect.w)
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
        outlineRoundRect(g, bx, by, boxSize, 33, 10, edge, bg)
        drawActionGlyph(g, icon, Rect(bx, by, boxSize, 33), if (active) TEXT else DISABLED)
        MpvUi.drawCentered(g, label, rect.x + rect.w / 2, rect.y + 37, when {
            !active -> DISABLED
            primary -> ACCENT
            else -> MUTED
        }, size = 8)
    }

    private fun drawPillButton(g: GuiGraphicsExtractor, rect: Rect, icon: MiniIcon, label: String, active: Boolean, hovered: Boolean) {
        val fill = when {
            !active -> CONTROL_DISABLED
            hovered -> CONTROL_HOVER
            else -> CONTROL_BG
        }
        outlineRoundRect(g, rect.x, rect.y, rect.w, rect.h, 10, if (hovered && active) 0xFF5B7283.toInt() else BORDER, fill)
        val color = if (active) TEXT else DISABLED
        val contentW = 13 + 5 + MpvUi.width(label, size = 9)
        val sx = rect.x + (rect.w - contentW) / 2
        drawMiniIcon(g, icon, sx, rect.y + 8, 12, color)
        MpvUi.draw(g, label, sx + 17, rect.y + 8, color, size = 9)
    }

    private fun drawTextButton(g: GuiGraphicsExtractor, rect: Rect, label: String, hovered: Boolean) {
        outlineRoundRect(g, rect.x, rect.y, rect.w, rect.h, 9, if (hovered) 0xFF5B7283.toInt() else BORDER, if (hovered) CONTROL_HOVER else CONTROL_BG)
        MpvUi.drawCentered(g, label, rect.x + rect.w / 2, rect.y + 9, if (hovered) TEXT else MUTED, size = 9)
    }

    private fun drawSmallActionButton(g: GuiGraphicsExtractor, rect: Rect, label: String, active: Boolean, hovered: Boolean) {
        outlineRoundRect(g, rect.x, rect.y, rect.w, rect.h, 6, if (hovered) 0xFF5B7283.toInt() else BORDER, when {
            !active -> CONTROL_DISABLED
            hovered -> CONTROL_HOVER
            else -> CONTROL_BG
        })
        MpvUi.drawCentered(g, label, rect.x + rect.w / 2, rect.y + 5, if (active) TEXT else DISABLED, size = 8)
    }

    private fun drawVolume(g: GuiGraphicsExtractor, rect: Rect, mouseX: Int, mouseY: Int) {
        val hovered = rect.contains(mouseX.toDouble(), mouseY.toDouble())
        val value = cfg.volume.coerceIn(0, 200)
        val centerY = rect.y + rect.h / 2
        fillRoundRect(g, rect.x, centerY - 3, rect.w, 6, 3, TRACK_BG)
        val fillW = (rect.w * value / 200.0).roundToInt().coerceIn(0, rect.w)
        if (fillW > 0) fillRoundRect(g, rect.x, centerY - 3, fillW, 6, 3, ACCENT)
        val knobX = (rect.x + fillW).coerceIn(rect.x + 5, rect.right - 5)
        fillRoundRect(g, knobX - 6, centerY - 6, 12, 12, 6, if (hovered || draggingVolume) 0xFFFFFFFF.toInt() else 0xFFE9F2F8.toInt())
    }

    private fun drawVideoOpacityCard(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val card = videoOpacityCardRect()
        val track = videoOpacityTrackRect()
        val hovered = card.contains(mouseX.toDouble(), mouseY.toDouble())
        outlineRoundRect(g, card.x, card.y, card.w, card.h, 10, if (hovered) 0xAA5D7585.toInt() else BORDER, if (hovered) CONTROL_HOVER else CONTROL_BG)
        MpvUi.draw(g, "Video opacity", card.x + 8, card.y + 6, TEXT, size = 9, bold = true)
        val percent = (cfg.videoOpacity.coerceIn(0f, 1f) * 100f).roundToInt()
        val value = "$percent%"
        MpvUi.draw(g, value, card.right - 8 - MpvUi.width(value, size = 8), card.y + 7, DIM, size = 8)
        val centerY = track.y + track.h / 2
        fillRoundRect(g, track.x, centerY - 2, track.w, 4, 2, TRACK_BG)
        val fillW = (track.w * cfg.videoOpacity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, track.w)
        if (fillW > 0) fillRoundRect(g, track.x, centerY - 2, fillW, 4, 2, ACCENT)
        val knobX = (track.x + fillW).coerceIn(track.x + 4, track.right - 4)
        fillRoundRect(g, knobX - 4, centerY - 4, 8, 8, 4, if (hovered || draggingVideoOpacity) 0xFFFFFFFF.toInt() else 0xFFE9F2F8.toInt())
    }

    private fun drawToggleCard(g: GuiGraphicsExtractor, spec: ToggleSpec, mouseX: Int, mouseY: Int) {
        val hovered = spec.active && spec.rect.contains(mouseX.toDouble(), mouseY.toDouble())
        val fill = when {
            !spec.active -> 0xA51E2A33.toInt()
            hovered -> CONTROL_HOVER
            else -> CONTROL_BG
        }
        outlineRoundRect(g, spec.rect.x, spec.rect.y, spec.rect.w, spec.rect.h, 10, if (hovered) 0xAA5D7585.toInt() else BORDER, fill)
        val titleColor = if (spec.active) TEXT else DISABLED
        val detailColor = if (spec.active) DIM else 0xFF62717D.toInt()
        MpvUi.draw(g, spec.title, spec.rect.x + 8, spec.rect.y + 6, titleColor, size = 9, bold = true)
        MpvUi.draw(g, MpvUi.clip(spec.detail, spec.rect.w - 48, size = 8), spec.rect.x + 8, spec.rect.y + 21, detailColor, size = 8)
        drawSwitch(g, spec.rect.right - 34, spec.rect.y + 11, spec.enabled, spec.active)
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

    private fun listChoices(): List<ListChoice> = when (rightTab) {
        RightTab.SUBTITLES -> {
            val subs = tracks.filter { it.type == "sub" }
            listOf(ListChoice(null, "None", subs.none { it.selected })) + subs.map { ListChoice(it.id, it.label(), it.selected) }
        }
        RightTab.AUDIO -> tracks.filter { it.type == "audio" }.map { ListChoice(it.id, it.label(), it.selected) }
        RightTab.CHAPTERS -> chapters.map { chapter -> ListChoice(chapter.index, chapter.title.ifBlank { "Chapter ${chapter.index + 1}" }, chapter.index == currentChapter, chapter) }
        RightTab.PLAYLIST -> MpvPlaylist.entries.mapIndexed { index, entry -> ListChoice(index, entry.displayName, index == MpvPlaylist.currentIndex) }
    }

    private fun visibleRowCount(): Int = (listRect().h / LIST_ROW_H).coerceAtLeast(1)
    private fun maxRightScroll(tab: RightTab = rightTab): Int {
        val old = rightTab
        if (tab == old) return (listChoices().size - visibleRowCount()).coerceAtLeast(0)
        rightTab = tab
        val result = (listChoices().size - visibleRowCount()).coerceAtLeast(0)
        rightTab = old
        return result
    }
    private fun rightScroll(): Int = rightScrolls[rightTab] ?: 0
    private fun setRightScroll(value: Int) { rightScrolls[rightTab] = value.coerceIn(0, maxRightScroll()) }
    private fun visibleChoices(): List<ListChoice> {
        val scroll = rightScroll().coerceIn(0, maxRightScroll())
        rightScrolls[rightTab] = scroll
        return listChoices().drop(scroll).take(visibleRowCount())
    }

    private fun ensureSelectedVisible(tab: RightTab) {
        val previousTab = rightTab
        rightTab = tab
        val choices = listChoices()
        val selected = choices.indexOfFirst { it.selected }
        if (selected >= 0) {
            val rows = visibleRowCount()
            val current = rightScrolls[tab] ?: 0
            val next = when {
                selected < current -> selected
                selected >= current + rows -> selected - rows + 1
                else -> current
            }
            rightScrolls[tab] = next.coerceIn(0, (choices.size - rows).coerceAtLeast(0))
        }
        rightTab = previousTab
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
            RightTab.PLAYLIST -> item.id?.let {
                if (MpvPlaylist.playIndex(it)) {
                    notice = "Opening ${MpvPlaylist.currentEntry?.displayName ?: "playlist item"}"
                    observedHasFile = false
                }
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

    private fun formatTimeShort(time: Double): String {
        val total = time.coerceAtLeast(0.0).toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    private fun skipIntro() {
        val target = MpvPlayer.skipIntro()
        if (target != null) {
            currentChapter = target.index
            notice = "Skipped to ${target.title.ifBlank { "chapter ${target.index + 1}" }}"
        } else notice = "No intro target ahead"
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = mouseButtonEvent.x()
        val my = mouseButtonEvent.y()
        if (mouseButtonEvent.button() != 0) return super.mouseClicked(mouseButtonEvent, doubled)

        if (openMediaChoice) {
            val (file, folder) = openMediaModalButtons()
            when {
                file.contains(mx, my) && !filePickerBusy -> { openMediaChoice = false; openMediaFile(); return true }
                folder.contains(mx, my) && !filePickerBusy -> { openMediaChoice = false; openMediaFolder(); return true }
                !openMediaModalRect().contains(mx, my) -> { openMediaChoice = false; return true }
                else -> return true
            }
        }

        if (infoHandleRect().contains(mx, my)) {
            infoDrawerOpen = !infoDrawerOpen
            return true
        }
        if (infoDrawerProgress > 0.85f && infoDrawerHudRect().contains(mx, my)) {
            cfg.save()
            MpvHudScreen.openFromMenu()
            return true
        }
        if (infoDrawerProgress > 0.02f && infoDrawerRect().contains(mx, my)) return true

        if (closeRect().contains(mx, my)) { onClose(); return true }
        if (moveUiRect().contains(mx, my)) { cfg.save(); MpvHudScreen.openFromMenu(); return true }

        playbackItemRects().forEachIndexed { index, rect ->
            if (!leftHit(rect, mx, my)) return@forEachIndexed
            when (index) {
                0 -> MpvPlaylist.previous()
                1 -> if (MpvPlayer.hasFile) MpvPlayer.seek(-5)
                2 -> if (MpvPlayer.hasFile) MpvPlayer.togglePause()
                3 -> if (MpvPlayer.hasFile) MpvPlayer.seek(5)
                4 -> MpvPlaylist.next()
                5 -> if (MpvPlayer.hasFile) { MpvPlayer.stop(); refreshMediaLists() }
                6 -> if (MpvPlayer.introTarget(chapters) != null) skipIntro()
            }
            return true
        }

        val timeline = timelineTrackRect()
        val timelineHit = Rect(timeline.x - 2, timeline.y - 7, timeline.w + 4, timeline.h + 14)
        if (leftHit(timelineHit, mx, my) && MpvPlayer.hasFile && MpvPlayer.durationSeconds > 0.0) {
            draggingTimeline = true
            lastTimelineSeek = Double.NaN
            updateTimelineFromMouse(mx, force = true)
            return true
        }

        val (open, addSub) = mediaButtonRects()
        if (leftHit(open, mx, my) && !filePickerBusy) { openMediaChoice = true; return true }
        if (leftHit(addSub, mx, my) && MpvPlayer.hasFile && !filePickerBusy) { addSubtitleFile(); return true }

        val volume = volumeTrackRect()
        val volumeHit = Rect(volume.x - 3, volume.y - 7, volume.w + 6, volume.h + 14)
        if (leftHit(volumeHit, mx, my)) {
            draggingVolume = true
            updateVolumeFromMouse(mx)
            return true
        }

        val opacity = videoOpacityTrackRect()
        val opacityHit = Rect(opacity.x - 3, opacity.y - 7, opacity.w + 6, opacity.h + 14)
        if (leftHit(opacityHit, mx, my)) {
            draggingVideoOpacity = true
            updateVideoOpacityFromMouse(mx)
            return true
        }

        (videoToggleSpecs() + subtitleToggleSpecs()).forEach { spec ->
            if (spec.active && leftHit(spec.rect, mx, my)) {
                notice = null
                spec.onClick()
                return true
            }
        }

        val sort = playlistSortRect()
        if (leftHit(sort, mx, my)) {
            MpvPlaylist.setSortMode(if (MpvPlaylist.sortMode == MpvPlaylist.SortMode.NAME) MpvPlaylist.SortMode.DATE_MODIFIED else MpvPlaylist.SortMode.NAME)
            ensureSelectedVisible(RightTab.PLAYLIST)
            cfg.save()
            return true
        }
        val direction = playlistDirectionRect()
        if (leftHit(direction, mx, my)) {
            MpvPlaylist.setAscending(!MpvPlaylist.ascending)
            ensureSelectedVisible(RightTab.PLAYLIST)
            cfg.save()
            return true
        }
        val auto = playlistAutoRect()
        if (leftHit(auto, mx, my)) {
            MpvPlaylist.setAutoPlayNext(!MpvPlaylist.autoPlayNext)
            cfg.save()
            return true
        }

        tabRects().forEach { (tab, rect) ->
            if (rect.contains(mx, my)) {
                rightTab = tab
                clampScrolls()
                ensureSelectedVisible(tab)
                return true
            }
        }

        val list = listRect()
        if (list.contains(mx, my)) {
            val row = ((my - list.y) / LIST_ROW_H).toInt()
            visibleChoices().getOrNull(row)?.let(::choose)
            return true
        }

        val bottom = browserBottomActionRect()
        if (bottom.contains(mx, my)) {
            if (rightTab == RightTab.CHAPTERS && MpvPlayer.introTarget(chapters) != null) skipIntro()
            if (rightTab == RightTab.PLAYLIST) MpvPlaylist.next()
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
        if (draggingPanel) { draggingPanel = false; cfg.save(); consumed = true }
        if (draggingVolume) { draggingVolume = false; cfg.save(); consumed = true }
        if (draggingVideoOpacity) { draggingVideoOpacity = false; cfg.save(); consumed = true }
        if (draggingTimeline) {
            updateTimelineFromMouse(mouseButtonEvent.x(), force = true)
            draggingTimeline = false
            lastTimelineSeek = Double.NaN
            lastTimelineSeekNanos = 0L
            consumed = true
        }
        return if (consumed) true else super.mouseReleased(mouseButtonEvent)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (openMediaChoice || infoDrawerProgress > 0.85f && infoDrawerRect().contains(mouseX, mouseY)) return true
        if (leftCard().contains(mouseX, mouseY)) {
            val step = -verticalAmount.sign.toInt()
            if (step != 0) leftScroll = (leftScroll + step * 8).coerceIn(0, maxLeftScroll())
            return true
        }
        if (listRect().contains(mouseX, mouseY)) {
            val step = -verticalAmount.sign.toInt()
            if (step != 0) setRightScroll(rightScroll() + step)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private fun updateVolumeFromMouse(mouseX: Double) {
        val rect = volumeTrackRect()
        val ratio = ((mouseX - rect.x) / rect.w.toDouble()).coerceIn(0.0, 1.0)
        val next = (ratio * 200.0).roundToInt().coerceIn(0, 200)
        if (cfg.volume != next) { cfg.volume = next; MpvPlayer.setVolume(next) }
    }

    private fun updateVideoOpacityFromMouse(mouseX: Double) {
        val rect = videoOpacityTrackRect()
        val ratio = ((mouseX - rect.x) / rect.w.toDouble()).coerceIn(0.0, 1.0)
        cfg.videoOpacity = (ratio * 100.0).roundToInt().coerceIn(0, 100) / 100f
    }

    private fun updateTimelineFromMouse(mouseX: Double, force: Boolean = false) {
        val rect = timelineTrackRect()
        val duration = MpvPlayer.durationSeconds
        if (duration <= 0.0) return
        val ratio = ((mouseX - rect.x) / rect.w.toDouble()).coerceIn(0.0, 1.0)
        val target = duration * ratio
        val now = System.nanoTime()
        if (!force && now - lastTimelineSeekNanos < 40_000_000L) return
        if (force || !lastTimelineSeek.isFinite() || abs(target - lastTimelineSeek) >= 0.15) {
            lastTimelineSeek = target
            lastTimelineSeekNanos = now
            MpvPlayer.seekAbsolute(target)
        }
    }

    private fun openMediaFile() = runPicker("media file", { NativeFilePicker.chooseMediaFile(it) }) { selected ->
        lastOpenDirectory = selected.parentFile?.absolutePath
        MpvPlaylist.openSingle(selected)
        observedHasFile = false
        notice = "Opening ${selected.name}"
        rightTab = RightTab.CHAPTERS
    }

    private fun openMediaFolder() = runPicker("media folder", { NativeFilePicker.chooseMediaFolder(it) }) { selected ->
        lastOpenDirectory = selected.absolutePath
        if (MpvPlaylist.openFolder(selected)) {
            observedHasFile = false
            notice = "Playlist loaded: ${MpvPlaylist.entries.size} items"
            rightTab = RightTab.PLAYLIST
        } else {
            filePickerError = "No supported media files in ${selected.name}"
        }
    }

    private fun addSubtitleFile() = runPicker("subtitle", { NativeFilePicker.chooseSubtitle(it) }) { selected ->
        lastOpenDirectory = selected.parentFile?.absolutePath
        if (MpvPlayer.addSubtitle(selected.absolutePath)) {
            notice = "Added subtitle ${selected.name}"
            rightTab = RightTab.SUBTITLES
            tickCounter = 0
        } else filePickerError = "Could not add subtitle ${selected.name}"
    }

    private fun runPicker(label: String, choose: (File?) -> File?, onSelected: (File) -> Unit) {
        if (filePickerBusy) return
        filePickerError = null
        notice = null
        filePickerBusy = true
        val initial = lastOpenDirectory?.let(::File) ?: mc.gameDirectory
        Thread({
            val result = runCatching { choose(initial) }
            mc.execute {
                filePickerBusy = false
                result.onSuccess { selected -> if (selected != null) onSelected(selected) }
                    .onFailure { t ->
                        MpvCraft.logger.error("Could not open the $label picker", t)
                        filePickerError = "Picker unavailable: ${t.message ?: t.javaClass.simpleName}"
                    }
            }
        }, "MpvCraft-$label-picker").apply { isDaemon = true; start() }
    }

    private fun maxLeftScroll(): Int = (LEFT_CONTENT_H - (leftCard().h - 20)).coerceAtLeast(0)
    private fun clampScrolls() {
        leftScroll = leftScroll.coerceIn(0, maxLeftScroll())
        RightTab.entries.forEach { tab ->
            val old = rightTab
            rightTab = tab
            rightScrolls[tab] = (rightScrolls[tab] ?: 0).coerceIn(0, maxRightScroll())
            rightTab = old
        }
    }

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
        draggingVideoOpacity = false
        draggingTimeline = false
        cfg.save()
        mc.setScreen(null)
    }

    override fun isPauseScreen(): Boolean = false

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

    private fun fillRoundRect(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int) {
        MpvUi.roundedRect(g, x, y, w, h, radius, color)
    }

    private fun outlineRoundRect(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, radius: Int, border: Int, fill: Int) {
        MpvUi.roundedRect(g, x, y, w, h, radius, fill, border, 1f)
    }

    private fun drawSmallTriangle(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: Int) {
        MpvUi.icon(g, MpvUi.Icon.PLAY, x, y, size, color)
    }

    private fun drawActionGlyph(g: GuiGraphicsExtractor, icon: ActionIcon, rect: Rect, color: Int) {
        val vector = when (icon) {
            ActionIcon.PREVIOUS -> MpvUi.Icon.PREVIOUS
            ActionIcon.PAUSE -> MpvUi.Icon.PAUSE
            ActionIcon.PLAY -> MpvUi.Icon.PLAY
            ActionIcon.REWIND -> MpvUi.Icon.REWIND_5
            ActionIcon.FORWARD -> MpvUi.Icon.FORWARD_5
            ActionIcon.NEXT, ActionIcon.SKIP -> MpvUi.Icon.SKIP
            ActionIcon.STOP -> MpvUi.Icon.STOP
        }
        val iconSize = when (icon) {
            ActionIcon.REWIND, ActionIcon.FORWARD -> minOf(rect.w, rect.h) - 10
            else -> minOf(rect.w, rect.h) - 12
        }.coerceAtLeast(10)
        MpvUi.icon(g, vector, rect.x + (rect.w - iconSize) / 2, rect.y + (rect.h - iconSize) / 2, iconSize, color)
    }

    private fun drawMiniIcon(g: GuiGraphicsExtractor, icon: MiniIcon, x: Int, y: Int, size: Int, color: Int) {
        val vector = when (icon) {
            MiniIcon.PLAYBACK -> MpvUi.Icon.PLAY
            MiniIcon.VOLUME -> MpvUi.Icon.VOLUME
            MiniIcon.DISPLAY -> MpvUi.Icon.DISPLAY
            MiniIcon.SUBTITLES -> MpvUi.Icon.SUBTITLES
            MiniIcon.AUDIO -> MpvUi.Icon.AUDIO
            MiniIcon.CHAPTERS -> MpvUi.Icon.CHAPTERS
            MiniIcon.PLAYLIST -> MpvUi.Icon.PLAYLIST
            MiniIcon.INFO -> MpvUi.Icon.INFO
            MiniIcon.LINK -> MpvUi.Icon.LINK
            MiniIcon.HEART -> MpvUi.Icon.HEART
            MiniIcon.MOVE -> MpvUi.Icon.MOVE
            MiniIcon.CLOSE -> MpvUi.Icon.CLOSE
            MiniIcon.FOLDER -> MpvUi.Icon.FOLDER
            MiniIcon.FILE -> MpvUi.Icon.FILE
        }
        MpvUi.icon(g, vector, x, y, size, color)
    }
}
