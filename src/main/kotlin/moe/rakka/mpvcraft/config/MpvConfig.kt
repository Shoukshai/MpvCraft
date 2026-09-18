package moe.rakka.mpvcraft.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import moe.rakka.mpvcraft.MpvCraft
import java.io.File

/**
 * Everything the user can move, resize or toggle. Serialized to
 * .minecraft/config/mpvcraft.json on screen close.
 */
data class MpvConfig(
    // video window
    var videoX: Int = 40,
    var videoY: Int = 40,
    /** Width in real pixels. Height follows the video aspect ratio. */
    var videoWidth: Int = 640,
    var videoEnabled: Boolean = true,
    /** Opacity of the in-game video surface, 0 = transparent, 1 = opaque. */
    var videoOpacity: Float = 1f,

    // subtitles, independent of the video window
    var subX: Int = 0,
    var subY: Int = 0,
    /** Keep detached subtitles horizontally centred as their text width changes. */
    var subCenterX: Boolean = false,
    var subScale: Float = 2f,
    var subEnabled: Boolean = true,
    /** Subtitles follow the bottom of the video window instead of using subX/subY. */
    var subAttached: Boolean = true,
    var subBackground: Boolean = true,
    var subMaxWidth: Int = 720,
    /** Zoom of detached bitmap subtitles; the editor only exposes the visible alpha crop. */
    var imageSubScale: Float = 1f,
    /** Keep detached bitmap subtitle cues horizontally centred as their crop changes. */
    var imageSubCenterX: Boolean = false,
    /** Translation applied to the source-fitted bitmap subtitle canvas. */
    var imageSubOffsetX: Int = 0,
    var imageSubOffsetY: Int = 0,

    var volume: Int = 80,

    // control panel position in GUI coordinates; old configs default to centered
    var menuPositionSet: Boolean = false,
    var menuX: Int = 0,
    var menuY: Int = 0,
    /** Vertical flip. If your video comes out upside down, toggle this. */
    var flipY: Boolean = true,
    /**
     * Passed to mpv as -hwdec. Off by default: on Windows the GL interop paths
     * want an ANGLE context and Minecraft runs on desktop WGL. "d3d11va-copy" is
     * the safe one to try if software decoding is not keeping up.
     */
    var hwdec: String = "no",
    /**
     * Optional explicit yt-dlp executable. Leave blank to let MpvCraft/mpv search
     * the game directory and PATH. Used only for ordinary web-page URL resolution.
     */
    var ytDlpPath: String = "",

    // playlist defaults
    var playlistSort: String = "name",
    var playlistSortAscending: Boolean = true,
    var playlistAutoNext: Boolean = true,
) {
    fun save() {
        try {
            FILE.parentFile?.mkdirs()
            FILE.writeText(GSON.toJson(this))
        } catch (t: Throwable) {
            MpvCraft.logger.error("Could not save config", t)
        }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()
        private val FILE: File by lazy {
            File(MpvCraft.mc.gameDirectory, "config/mpvcraft.json")
        }

        fun load(): MpvConfig = try {
            if (!FILE.exists()) {
                MpvConfig()
            } else {
                val root = JsonParser.parseString(FILE.readText()).asJsonObject
                val loaded = GSON.fromJson(root, MpvConfig::class.java) ?: MpvConfig()

                // Gson can leave newly introduced primitive fields at zero when an old
                // config is loaded. Use field presence for defaults where zero itself is
                // a valid user value (notably fully transparent video).
                if (!root.has("videoOpacity")) loaded.videoOpacity = 1f
                if (!root.has("imageSubCenterX")) loaded.imageSubCenterX = false
                if (!root.has("imageSubScale") || !loaded.imageSubScale.isFinite() || loaded.imageSubScale <= 0f) {
                    loaded.imageSubScale = 1f
                }
                if (!root.has("playlistSort")) loaded.playlistSort = "name"
                if (!root.has("playlistSortAscending")) loaded.playlistSortAscending = true
                if (!root.has("playlistAutoNext")) loaded.playlistAutoNext = true

                loaded.videoOpacity = loaded.videoOpacity.coerceIn(0f, 1f)
                loaded.imageSubScale = loaded.imageSubScale.coerceIn(0.35f, 3f)
                loaded.playlistSort = when (loaded.playlistSort.lowercase()) {
                    "date", "date_modified" -> "date_modified"
                    else -> "name"
                }
                loaded
            }
        } catch (t: Throwable) {
            MpvCraft.logger.error("Could not read config, using defaults", t)
            MpvConfig()
        }
    }
}
