package moe.rakka.mpvcraft.mpv

import moe.rakka.mpvcraft.MpvCraft
import java.io.File
import java.util.Locale

object MpvPlaylist {

    enum class SortMode {
        NAME,
        DATE_MODIFIED,
    }

    data class Entry(
        val file: File,
        val displayName: String = file.name,
    )

    private val mediaExtensions = setOf(
        "mkv", "mp4", "webm", "avi", "mov", "m4v", "mpeg", "mpg", "ogv",
        "ts", "m2ts", "mts", "m2v", "flv", "wmv", "vob", "mxf", "3gp", "3g2",
        "mp3", "flac", "ogg", "oga", "wav", "m4a", "aac", "opus", "mka", "wma",
        "aiff", "aif", "alac", "ape", "wv",
    )

    var entries: List<Entry> = emptyList()
        private set

    var currentIndex: Int = -1
        private set

    var sourceDirectory: File? = null
        private set

    var sortMode: SortMode = SortMode.NAME
        private set

    var ascending: Boolean = true
        private set

    var autoPlayNext: Boolean = true
        private set

    private var observedNaturalEndSerial: Long = 0L

    val active: Boolean
        get() = entries.isNotEmpty()

    val currentEntry: Entry?
        get() = entries.getOrNull(currentIndex)

    fun initializeFromConfig() {
        sortMode = when (MpvCraft.config.playlistSort.lowercase()) {
            "date", "date_modified" -> SortMode.DATE_MODIFIED
            else -> SortMode.NAME
        }
        ascending = MpvCraft.config.playlistSortAscending
        autoPlayNext = MpvCraft.config.playlistAutoNext
        observedNaturalEndSerial = MpvPlayer.naturalEndSerial
    }

    fun openSingle(file: File) {
        clear()
        MpvPlayer.load(file.absolutePath)
        MpvPlayer.setVolume(MpvCraft.config.volume)
    }

    fun openFolder(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val files = directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && isSupportedMedia(it) }
            ?.map(::Entry)
            ?.toList()
            .orEmpty()

        if (files.isEmpty()) {
            clear()
            sourceDirectory = directory
            return false
        }

        sourceDirectory = directory
        entries = sorted(files)
        currentIndex = 0
        observedNaturalEndSerial = MpvPlayer.naturalEndSerial
        playIndex(0)
        return true
    }

    fun clear() {
        entries = emptyList()
        currentIndex = -1
        sourceDirectory = null
        observedNaturalEndSerial = MpvPlayer.naturalEndSerial
    }

    fun playIndex(index: Int): Boolean {
        val entry = entries.getOrNull(index) ?: return false
        currentIndex = index
        observedNaturalEndSerial = MpvPlayer.naturalEndSerial
        MpvPlayer.load(entry.file.absolutePath)
        MpvPlayer.setVolume(MpvCraft.config.volume)
        return true
    }

    fun previous(): Boolean = if (currentIndex > 0) playIndex(currentIndex - 1) else false

    fun next(): Boolean = if (currentIndex in 0 until entries.lastIndex) playIndex(currentIndex + 1) else false

    fun setSortMode(mode: SortMode) {
        if (sortMode == mode) return
        sortMode = mode
        MpvCraft.config.playlistSort = if (mode == SortMode.NAME) "name" else "date_modified"
        resortPreservingCurrent()
    }

    fun setAscending(value: Boolean) {
        if (ascending == value) return
        ascending = value
        MpvCraft.config.playlistSortAscending = value
        resortPreservingCurrent()
    }

    fun setAutoPlayNext(value: Boolean) {
        autoPlayNext = value
        MpvCraft.config.playlistAutoNext = value
    }

    fun tick() {
        val serial = MpvPlayer.naturalEndSerial
        if (serial == observedNaturalEndSerial) return
        observedNaturalEndSerial = serial
        if (!autoPlayNext || !active) return
        if (currentIndex in 0 until entries.lastIndex) {
            playIndex(currentIndex + 1)
        }
    }

    private fun resortPreservingCurrent() {
        if (entries.isEmpty()) return
        val currentPath = currentEntry?.file?.absolutePath
        entries = sorted(entries)
        currentIndex = if (currentPath == null) {
            -1
        } else {
            entries.indexOfFirst { it.file.absolutePath == currentPath }
        }
    }

    private fun sorted(input: List<Entry>): List<Entry> {
        val comparator = when (sortMode) {
            SortMode.NAME -> compareBy<Entry> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.displayName }
            SortMode.DATE_MODIFIED -> compareBy<Entry> { it.file.lastModified() }.thenBy { it.displayName.lowercase(Locale.ROOT) }
        }
        return if (ascending) input.sortedWith(comparator) else input.sortedWith(comparator.reversed())
    }

    private fun isSupportedMedia(file: File): Boolean =
        file.extension.lowercase(Locale.ROOT) in mediaExtensions
}
