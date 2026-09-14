package moe.rakka.mpvcraft.hud

import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.MpvCraft.mc
import moe.rakka.mpvcraft.mpv.MpvPlayer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Main MpvCraft control surface.
 *
 * V5.4 removes popup dropdowns entirely. Subtitle/audio/chapter selection lives in
 * a fixed right-hand browser, so it cannot overlap or click through other widgets.
 */
object MpvMenuScreen : Screen(MpvUi.text("MpvCraft")) {

    private const val MAX_PANEL_W = 600
    private const val PANEL_H = 340
    private const val PAD = 16
    private const val COLUMN_GAP = 12
    private const val BUTTON_H = 20
    private const val GAP = 4
    private const val PANEL_MARGIN = 8
    private const val LIST_ROW_H = 21
    private const val LIST_ROWS = 4

    private const val PANEL_BG = 0xE014171D.toInt()
    private const val CARD_BG = 0xB51C2129.toInt()
    private const val LIST_BG = 0xF0181D24.toInt()
    private const val BORDER = 0xFF343C48.toInt()
    private const val ACCENT = 0xFF58C7FF.toInt()
    private const val TEXT = 0xFFF4F7FA.toInt()
    private const val MUTED = 0xFFA8B1BE.toInt()
    private const val DISABLED = 0xFF717986.toInt()
    private const val GOOD = 0xFF77D69A.toInt()
    private const val WARN = 0xFFFFC66D.toInt()
    private const val BAD = 0xFFFF7A7A.toInt()
    private const val SELECTED_BG = 0xFF293541.toInt()
    private const val HOVER_BG = 0xFF252D37.toInt()

    private enum class RightTab { SUBTITLES, AUDIO, CHAPTERS }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double): Boolean =
            px >= x && py >= y && px < x + w && py < y + h
    }

    private data class ListChoice(
        val id: Int?,
        val label: String,
        val selected: Boolean,
    )

    private data class WidgetLabel(
        val widget: AbstractWidget,
        val label: () -> String,
    )

    private val cfg get() = MpvCraft.config

    private var tracks: List<MpvPlayer.Track> = emptyList()
    private var chapters: List<MpvPlayer.Chapter> = emptyList()
    private var currentChapter = -1
    private var rightTab = RightTab.SUBTITLES
    private var listScroll = 0
    private var tickCounter = 0

    private val movableWidgets = mutableListOf<AbstractWidget>()
    private val widgetLabels = mutableListOf<WidgetLabel>()
    private var volumeSlider: VolumeSlider? = null

    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = PANEL_H
    private var leftX = 0
    private var rightX = 0
    private var colW = 0
    private var contentTop = 0
    private var listTop = 0
    private var tabY = 0
    private val tabWidgets = mutableMapOf<RightTab, AbstractWidget>()

    private var draggingPanel = false
    private var panelGrabX = 0
    private var panelGrabY = 0

    private var observedHasFile = false
    private var observedSubtitlePresentation = MpvPlayer.SubtitlePresentation.NONE
    @Volatile private var filePickerBusy = false
    private var filePickerError: String? = null
    private var notice: String? = null
    private var lastOpenDirectory: String? = null

    override fun init() {
        movableWidgets.clear()
        widgetLabels.clear()
        tabWidgets.clear()
        volumeSlider = null

        panelW = minOf(MAX_PANEL_W, (width - 24).coerceAtLeast(420))
        panelH = minOf(PANEL_H, (height - 24).coerceAtLeast(300))

        if (cfg.menuPositionSet) {
            panelX = clampPanelX(cfg.menuX)
            panelY = clampPanelY(cfg.menuY)
        } else {
            panelX = (width - panelW) / 2
            panelY = ((height - panelH) / 2).coerceAtLeast(12)
        }

        calculateLayout()
        refreshMediaLists()
        observedHasFile = MpvPlayer.hasFile
        MpvPlayer.syncSubtitlePresentation(cfg.subEnabled)
        observedSubtitlePresentation = MpvPlayer.subtitlePresentation

        // -----------------------------------------------------------------
        // Playback. Skip intro is also globally available through the configurable
        // MpvCraft key mapping (N by default).
        val playbackY = contentTop + 16
        val playbackAvailable = (colW - GAP * 4).coerceAtLeast(120)
        val playW = (playbackAvailable * 34 / 100).coerceAtLeast(40)
        val seekW = (playbackAvailable * 13 / 100).coerceAtLeast(24)
        val skipW = (playbackAvailable * 22 / 100).coerceAtLeast(36)
        val stopW = (playbackAvailable - playW - seekW * 2 - skipW).coerceAtLeast(28)
        var px = leftX
        addButton(px, playbackY, playW, if (MpvPlayer.paused) "Play" else "Pause", MpvPlayer.hasFile) {
            MpvPlayer.togglePause()
        }
        px += playW + GAP
        addButton(px, playbackY, seekW, "-10s", MpvPlayer.hasFile) { MpvPlayer.seek(-10) }
        px += seekW + GAP
        addButton(px, playbackY, seekW, "+10s", MpvPlayer.hasFile) { MpvPlayer.seek(10) }
        px += seekW + GAP
        addButton(px, playbackY, skipW, "Skip OP", MpvPlayer.introTarget(chapters) != null) {
            val target = MpvPlayer.skipIntro()
            if (target != null) {
                currentChapter = target.index
                notice = "Skipped to ${target.title.ifBlank { "chapter ${target.index + 1}" }}"
            } else {
                notice = "No intro target ahead"
            }
        }
        px += skipW + GAP
        addButton(px, playbackY, stopW, "Stop", MpvPlayer.hasFile) {
            MpvPlayer.stop()
            tracks = emptyList()
            chapters = emptyList()
            currentChapter = -1
            listScroll = 0
        }

        // Volume: 0..200%.
        val volumeY = contentTop + 65
        volumeSlider = addTracked(
            VolumeSlider(leftX, volumeY, colW, cfg.volume) { value ->
                if (cfg.volume != value) {
                    cfg.volume = value
                    MpvPlayer.setVolume(value)
                }
            }
        )

        // Display / HUD
        val half = (colW - GAP) / 2
        val displayY = contentTop + 114
        addButton(leftX, displayY, half, "Video: ${onOff(cfg.videoEnabled)}") {
            cfg.videoEnabled = !cfg.videoEnabled
        }
        addButton(leftX + half + GAP, displayY, half, "Subtitles: ${onOff(cfg.subEnabled)}") {
            cfg.subEnabled = !cfg.subEnabled
            MpvPlayer.setSubtitlesEnabled(cfg.subEnabled)
        }
        val nativeImageSubs = MpvPlayer.usesNativeImageSubtitles
        addButton(
            leftX,
            displayY + 24,
            half,
            if (nativeImageSubs) "Subs: native image" else if (cfg.subAttached) "Subs: attached" else "Subs: detached",
            active = !nativeImageSubs,
        ) {
            cfg.subAttached = !cfg.subAttached
        }
        addButton(
            leftX + half + GAP,
            displayY + 24,
            half,
            if (nativeImageSubs) "Image styling: native" else "Background: ${onOff(cfg.subBackground)}",
            active = !nativeImageSubs,
        ) {
            cfg.subBackground = !cfg.subBackground
        }
        addButton(leftX, displayY + 48, colW, "Flip image: ${onOff(cfg.flipY)}") {
            cfg.flipY = !cfg.flipY
            MpvPlayer.flipY = cfg.flipY
        }
        addButton(leftX, displayY + 76, colW, "Edit HUD layout", rebuild = false) {
            cfg.save()
            mc.setScreen(MpvHudScreen)
        }

        // -----------------------------------------------------------------
        // Fixed browser tabs. No popup/dropdown exists in V5.4.
        tabY = contentTop + 16
        listTop = contentTop + 42
        val tabGap = 3
        val tabW = (colW - tabGap * 2) / 3
        addTab(RightTab.SUBTITLES, rightX, tabY, tabW, "Subs")
        addTab(RightTab.AUDIO, rightX + tabW + tabGap, tabY, tabW, "Audio")
        addTab(
            RightTab.CHAPTERS,
            rightX + (tabW + tabGap) * 2,
            tabY,
            colW - tabW * 2 - tabGap * 2,
            "Chapters",
        )

        // Footer.
        addButton(
            panelX + PAD,
            panelY + panelH - 31,
            108,
            "Open file...",
            active = !filePickerBusy,
            rebuild = false,
        ) {
            openLocalFile()
        }
        addButton(panelX + panelW - PAD - 86, panelY + panelH - 31, 86, "Done", rebuild = false) {
            onClose()
        }
    }

    override fun tick() {
        super.tick()
        tickCounter++
        MpvPlayer.syncSubtitlePresentation(cfg.subEnabled)

        val now = MpvPlayer.hasFile
        val presentation = MpvPlayer.subtitlePresentation
        if (now != observedHasFile || presentation != observedSubtitlePresentation) {
            observedHasFile = now
            observedSubtitlePresentation = presentation
            refreshMediaLists()
            rebuildWidgets()
            return
        }

        if (now && tickCounter % 10 == 0) {
            currentChapter = MpvPlayer.currentChapterIndex()
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
        val innerW = panelW - PAD * 2
        colW = (innerW - COLUMN_GAP) / 2
        leftX = panelX + PAD
        rightX = leftX + colW + COLUMN_GAP
        contentTop = panelY + 66
    }

    private fun <T : AbstractWidget> addTracked(widget: T): T {
        movableWidgets += widget
        addRenderableWidget(widget)
        return widget
    }

    private fun addButton(
        x: Int,
        y: Int,
        w: Int,
        label: String,
        active: Boolean = true,
        rebuild: Boolean = true,
        action: () -> Unit,
    ): Button {
        val widget = Button.builder(Component.empty()) {
            action()
            if (rebuild) rebuildWidgets()
        }.bounds(x, y, w, BUTTON_H).build()
        widget.active = active
        widgetLabels += WidgetLabel(widget) { label }
        return addTracked(widget)
    }

    private fun addTab(tab: RightTab, x: Int, y: Int, w: Int, label: String) {
        val widget = addButton(x, y, w, label, rebuild = false) {
            if (rightTab != tab) {
                rightTab = tab
                listScroll = 0
            }
        }
        tabWidgets[tab] = widget
    }

    private class VolumeSlider(
        x: Int,
        y: Int,
        width: Int,
        initial: Int,
        private val changed: (Int) -> Unit,
    ) : AbstractSliderButton(
        x,
        y,
        width,
        BUTTON_H,
        Component.empty(),
        initial.coerceIn(0, 200) / 200.0,
    ) {
        private var lastValue = initial.coerceIn(0, 200)

        override fun updateMessage() {
            setMessage(Component.empty())
        }

        override fun applyValue() {
            val next = currentValue()
            if (next != lastValue) {
                lastValue = next
                changed(next)
            }
        }

        fun displayLabel(): String = "Volume  ${currentValue()}%"

        private fun currentValue(): Int = (value * 200.0).roundToInt().coerceIn(0, 200)
    }

    private fun listChoices(): List<ListChoice> = when (rightTab) {
        RightTab.SUBTITLES -> {
            val subs = tracks.filter { it.type == "sub" }
            listOf(ListChoice(null, "None", subs.none { it.selected })) +
                subs.map { ListChoice(it.id, it.label(), it.selected) }
        }
        RightTab.AUDIO -> tracks.filter { it.type == "audio" }
            .map { ListChoice(it.id, it.label(), it.selected) }
        RightTab.CHAPTERS -> chapters.map { chapter ->
            ListChoice(chapter.index, chapter.label(), chapter.index == currentChapter)
        }
    }

    private fun listRect(): Rect = Rect(rightX, listTop, colW, LIST_ROWS * LIST_ROW_H)

    private fun maxListScroll(): Int = (listChoices().size - LIST_ROWS).coerceAtLeast(0)

    private fun visibleChoices(): List<ListChoice> {
        listScroll = listScroll.coerceIn(0, maxListScroll())
        return listChoices().drop(listScroll).take(LIST_ROWS)
    }

    private fun choose(item: ListChoice) {
        when (rightTab) {
            RightTab.SUBTITLES -> {
                MpvPlayer.selectTrack("sub", item.id)
                MpvPlayer.syncSubtitlePresentation(cfg.subEnabled, force = true)
                tracks = if (MpvPlayer.hasFile) MpvPlayer.tracks() else emptyList()
            }
            RightTab.AUDIO -> {
                MpvPlayer.selectTrack("audio", item.id)
                tracks = if (MpvPlayer.hasFile) MpvPlayer.tracks() else emptyList()
            }
            RightTab.CHAPTERS -> {
                item.id?.let {
                    MpvPlayer.selectChapter(it)
                    currentChapter = it
                }
            }
        }
    }

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)

        graphics.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, 0x66000000)
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG)
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 2, ACCENT)

        val cardsTop = contentTop - 10
        val cardsBottom = panelY + panelH - 43
        graphics.fill(leftX - 8, cardsTop, leftX + colW + 8, cardsBottom, CARD_BG)
        graphics.fill(rightX - 8, cardsTop, rightX + colW + 8, cardsBottom, CARD_BG)
        graphics.fill(leftX - 8, cardsTop, leftX + colW + 8, cardsTop + 1, BORDER)
        graphics.fill(rightX - 8, cardsTop, rightX + colW + 8, cardsTop + 1, BORDER)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        deltaTicks: Float,
    ) {
        if (draggingPanel) movePanelTo(mouseX - panelGrabX, mouseY - panelGrabY)

        super.extractRenderState(graphics, mouseX, mouseY, deltaTicks)

        // Header
        MpvUi.draw(graphics, "MpvCraft", panelX + PAD, panelY + 12, TEXT, MpvUi.TITLE_SIZE, bold = true)
        MpvUi.draw(
            graphics,
            MpvUi.clip(statusLine(), panelW - PAD * 2 - 120),
            panelX + PAD,
            panelY + 31,
            statusColor(),
        )
        val dragHint = "Drag to move"
        MpvUi.draw(
            graphics,
            dragHint,
            panelX + panelW - PAD - MpvUi.width(dragHint),
            panelY + 14,
            MUTED,
        )

        section(graphics, leftX, contentTop, "PLAYBACK")
        section(graphics, leftX, contentTop + 49, "VOLUME")
        section(graphics, leftX, contentTop + 98, "DISPLAY")

        section(graphics, rightX, contentTop, "BROWSER")
        drawTabAccent(graphics)
        drawBrowser(graphics, mouseX, mouseY)

        section(graphics, rightX, contentTop + 135, "MEDIA")
        val infoY = contentTop + 152
        val source = if (MpvPlayer.videoWidth > 0 && MpvPlayer.videoHeight > 0) {
            "Source  ${MpvPlayer.videoWidth} x ${MpvPlayer.videoHeight}"
        } else "Source  --"
        val surface = if (MpvPlayer.targetWidth > 0 && MpvPlayer.targetHeight > 0) {
            "Render  ${MpvPlayer.targetWidth} x ${MpvPlayer.targetHeight}"
        } else "Render  --"
        MpvUi.draw(graphics, source, rightX, infoY, MUTED)
        MpvUi.draw(graphics, surface, rightX, infoY + 15, MUTED)
        MpvUi.draw(graphics, "HUD     ${cfg.videoWidth} x ${MpvHud.videoHeight()} px", rightX, infoY + 30, MUTED)
        val chapterText = when {
            chapters.isEmpty() -> "Chapter --"
            currentChapter < 0 -> "Chapter --/${chapters.size}"
            else -> "Chapter ${currentChapter + 1}/${chapters.size}"
        }
        MpvUi.draw(graphics, chapterText, rightX, infoY + 45, MUTED)

        val footerHint = when {
            filePickerBusy -> "Opening file picker..."
            filePickerError != null -> filePickerError!!
            notice != null -> notice!!
            else -> "Local file picker - /mpv play also accepts URLs"
        }
        MpvUi.draw(
            graphics,
            MpvUi.clip(footerHint, (panelW - PAD * 2 - 108 - 86 - 20).coerceAtLeast(80), size = 10),
            panelX + PAD + 116,
            panelY + panelH - 25,
            when {
                filePickerError != null -> BAD
                filePickerBusy -> WARN
                notice != null -> GOOD
                else -> MUTED
            },
            size = 10,
        )

        drawWidgetLabels(graphics)
    }

    private fun drawTabAccent(graphics: GuiGraphicsExtractor) {
        val widget = tabWidgets[rightTab] ?: return
        graphics.fill(
            widget.getX() + 2,
            widget.getY() + widget.getHeight() - 2,
            widget.getX() + widget.getWidth() - 2,
            widget.getY() + widget.getHeight(),
            ACCENT,
        )
    }

    private fun drawBrowser(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rect = listRect()
        graphics.fill(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, LIST_BG)
        graphics.fill(rect.x, rect.y, rect.x + rect.w, rect.y + 1, BORDER)
        graphics.fill(rect.x, rect.y + rect.h - 1, rect.x + rect.w, rect.y + rect.h, BORDER)

        val all = listChoices()
        val visible = visibleChoices()
        if (visible.isEmpty()) {
            val empty = when (rightTab) {
                RightTab.SUBTITLES -> "No subtitle tracks"
                RightTab.AUDIO -> "No audio tracks"
                RightTab.CHAPTERS -> "No chapters"
            }
            MpvUi.drawCentered(graphics, empty, rect.x + rect.w / 2, rect.y + 12, MUTED)
            return
        }

        val lineH = MpvUi.lineHeight()
        visible.forEachIndexed { row, choice ->
            val y = rect.y + row * LIST_ROW_H
            val hovered = mouseX >= rect.x && mouseX < rect.x + rect.w &&
                mouseY >= y && mouseY < y + LIST_ROW_H
            if (choice.selected) {
                graphics.fill(rect.x + 1, y + 1, rect.x + rect.w - 1, y + LIST_ROW_H, SELECTED_BG)
            } else if (hovered) {
                graphics.fill(rect.x + 1, y + 1, rect.x + rect.w - 1, y + LIST_ROW_H, HOVER_BG)
            }
            if (row > 0) graphics.fill(rect.x + 2, y, rect.x + rect.w - 2, y + 1, 0xFF252C35.toInt())

            val prefix = if (choice.selected) "[x] " else "    "
            val reserved = if (all.size > LIST_ROWS) 12 else 4
            val label = MpvUi.clip(prefix + choice.label, (rect.w - 12 - reserved).coerceAtLeast(20))
            MpvUi.draw(
                graphics,
                label,
                rect.x + 6,
                y + (LIST_ROW_H - lineH) / 2,
                if (choice.selected) TEXT else MUTED,
            )
        }

        if (all.size > LIST_ROWS) {
            val trackX = rect.x + rect.w - 5
            graphics.fill(trackX, rect.y + 3, trackX + 2, rect.y + rect.h - 3, 0xFF303844.toInt())
            val maxStart = all.size - LIST_ROWS
            val usable = (rect.h - 8).coerceAtLeast(8)
            val thumbH = (usable * LIST_ROWS / all.size).coerceAtLeast(10)
            val travel = (usable - thumbH).coerceAtLeast(0)
            val thumbY = rect.y + 4 + if (maxStart == 0) 0 else travel * listScroll / maxStart
            graphics.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbH, ACCENT)
        }
    }

    private fun drawWidgetLabels(graphics: GuiGraphicsExtractor) {
        val lineH = MpvUi.lineHeight()
        widgetLabels.forEach { entry ->
            val w = entry.widget
            if (!w.visible) return@forEach
            val available = (w.getWidth() - 12).coerceAtLeast(1)
            val label = MpvUi.clip(entry.label(), available)
            val color = if (w.active) TEXT else DISABLED
            MpvUi.drawCentered(
                graphics,
                label,
                w.getX() + w.getWidth() / 2,
                w.getY() + (w.getHeight() - lineH) / 2,
                color,
            )
        }

        volumeSlider?.let { slider ->
            if (slider.visible) {
                val label = slider.displayLabel()
                MpvUi.drawCentered(
                    graphics,
                    MpvUi.clip(label, (slider.getWidth() - 12).coerceAtLeast(1)),
                    slider.getX() + slider.getWidth() / 2,
                    slider.getY() + (slider.getHeight() - lineH) / 2,
                    if (slider.active) TEXT else DISABLED,
                )
            }
        }
    }

    private fun section(g: GuiGraphicsExtractor, x: Int, y: Int, label: String) {
        MpvUi.draw(g, label, x, y, MUTED, size = MpvUi.SECTION_SIZE, bold = true)
    }

    private fun statusLine(): String = when {
        filePickerBusy -> "Opening file picker..."
        filePickerError != null -> filePickerError!!
        MpvPlayer.failureReason != null ->
            "libmpv unavailable - ${MpvPlayer.failureReason ?: "initialization failed"}"
        !MpvPlayer.available -> "Ready - libmpv initializes on first play"
        !MpvPlayer.hasFile -> "Ready - no media loaded"
        else -> {
            val state = if (MpvPlayer.paused) "Paused" else "Playing"
            "$state - ${MpvPlayer.title.ifBlank { "media" }}"
        }
    }

    private fun statusColor(): Int = when {
        filePickerBusy -> WARN
        filePickerError != null -> BAD
        MpvPlayer.failureReason != null -> BAD
        !MpvPlayer.hasFile -> WARN
        else -> GOOD
    }

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    // -----------------------------------------------------------------
    // Native local file picker

    private fun openLocalFile() {
        if (filePickerBusy) return
        filePickerError = null
        notice = null
        filePickerBusy = true
        rebuildWidgets()

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
                    }
                }.onFailure { t ->
                    MpvCraft.logger.error("Could not open the media file picker", t)
                    filePickerError = "File picker unavailable: ${t.message ?: t.javaClass.simpleName}"
                }
                if (mc.screen === this) rebuildWidgets()
            }
        }, "MpvCraft-file-picker").apply {
            isDaemon = true
            start()
        }
    }

    // -----------------------------------------------------------------
    // Browser input + movable panel

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = mouseButtonEvent.x()
        val my = mouseButtonEvent.y()

        if (mouseButtonEvent.button() == 0) {
            val rect = listRect()
            if (rect.contains(mx, my)) {
                val row = ((my - rect.y) / LIST_ROW_H).toInt()
                visibleChoices().getOrNull(row)?.let(::choose)
                return true
            }

            if (
                mx >= panelX && mx < panelX + panelW &&
                my >= panelY && my < panelY + 52
            ) {
                draggingPanel = true
                panelGrabX = (mx - panelX).toInt()
                panelGrabY = (my - panelY).toInt()
                return true
            }
        }

        return super.mouseClicked(mouseButtonEvent, doubled)
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

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        if (draggingPanel) {
            draggingPanel = false
            cfg.save()
            return true
        }
        return super.mouseReleased(mouseButtonEvent)
    }

    private fun movePanelTo(requestedX: Int, requestedY: Int) {
        val nextX = clampPanelX(requestedX)
        val nextY = clampPanelY(requestedY)
        val dx = nextX - panelX
        val dy = nextY - panelY
        if (dx == 0 && dy == 0) return

        panelX = nextX
        panelY = nextY
        leftX += dx
        rightX += dx
        contentTop += dy
        tabY += dy
        listTop += dy

        movableWidgets.forEach { widget ->
            widget.setX(widget.getX() + dx)
            widget.setY(widget.getY() + dy)
        }

        cfg.menuPositionSet = true
        cfg.menuX = panelX
        cfg.menuY = panelY
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
        cfg.save()
        mc.setScreen(null)
    }

    override fun isPauseScreen(): Boolean = false
}
