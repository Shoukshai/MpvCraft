package moe.rakka.mpvcraft.mpv

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference

/**
 * Raw JNA bindings against libmpv.
 *
 * Only the handful of entry points we actually need are declared. The C headers
 * these mirror are client.h, render.h and render_gl.h from the mpv source tree.
 *
 * Library name resolution:
 *   Linux   -> libmpv.so.2       (JNA name "mpv.so.2", or plain "mpv")
 *   Windows -> mpv-2.dll         (JNA name "mpv-2")
 *   macOS   -> libmpv.2.dylib
 */
@Suppress("FunctionName", "unused")
interface MpvLibrary : Library {

    // ---- client.h -------------------------------------------------------

    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_command(ctx: Pointer, args: StringArray): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String?

    // ---- render.h / render_gl.h ----------------------------------------

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_set_update_callback(ctx: Pointer, cb: UpdateCallback?, cbCtx: Pointer?)
    fun mpv_render_context_report_swap(ctx: Pointer)
    fun mpv_render_context_free(ctx: Pointer)

    /** `void (*mpv_render_update_fn)(void *cb_ctx)` */
    fun interface UpdateCallback : Callback {
        fun invoke(cbCtx: Pointer?)
    }

    /** `void *(*get_proc_address)(void *ctx, const char *name)` */
    fun interface GetProcAddress : Callback {
        fun invoke(ctx: Pointer?, name: String): Pointer?
    }

    companion object {
        /** Candidate library names, tried in order. */
        private val CANDIDATES: List<String> = when {
            System.getProperty("os.name").startsWith("Windows", true) ->
                listOf("mpv-2", "mpv-1", "libmpv-2", "mpv")
            System.getProperty("os.name").startsWith("Mac", true) ->
                listOf("mpv.2", "mpv")
            else ->
                listOf("mpv.so.2", "mpv", "mpv.so.1")
        }

        /**
         * Loads libmpv, or returns null with the collected failures in [errors].
         * An explicit path can be forced with -Dmpvcraft.libmpv=/path/to/libmpv.so.2
         */
        fun load(errors: MutableList<String>): MpvLibrary? {
            val override = System.getProperty("mpvcraft.libmpv")
            val names = if (override.isNullOrBlank()) CANDIDATES else listOf(override)
            for (name in names) {
                try {
                    return Native.load(
                        name,
                        MpvLibrary::class.java,
                        mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
                    )
                } catch (t: Throwable) {
                    errors += "$name: ${t.message}"
                }
            }
            return null
        }
    }
}

// ---- structs ------------------------------------------------------------

/** `struct mpv_render_param { enum type; void *data; }` */
@Structure.FieldOrder("type", "data")
open class MpvRenderParam : Structure() {
    @JvmField var type: Int = 0
    @JvmField var data: Pointer? = null
}

/** `struct mpv_opengl_init_params { get_proc_address; get_proc_address_ctx; }` */
@Structure.FieldOrder("get_proc_address", "get_proc_address_ctx")
open class MpvOpenGLInitParams : Structure() {
    @JvmField var get_proc_address: MpvLibrary.GetProcAddress? = null
    @JvmField var get_proc_address_ctx: Pointer? = null
}

/** `struct mpv_opengl_fbo { int fbo; int w; int h; int internal_format; }` */
@Structure.FieldOrder("fbo", "w", "h", "internal_format")
open class MpvOpenGLFbo : Structure() {
    @JvmField var fbo: Int = 0
    @JvmField var w: Int = 0
    @JvmField var h: Int = 0
    @JvmField var internal_format: Int = 0
}

/** `struct mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; }` */
@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
open class MpvEvent(p: Pointer) : Structure(p) {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null
}

/** `struct mpv_event_property { const char *name; int format; void *data; }` */
@Structure.FieldOrder("name", "format", "data")
open class MpvEventProperty(p: Pointer) : Structure(p) {
    @JvmField var name: String? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null
}


// ---- constants ----------------------------------------------------------

object Mpv {
    // mpv_format
    const val FORMAT_NONE = 0
    const val FORMAT_STRING = 1
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5

    // mpv_event_id
    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_LOG_MESSAGE = 2
    const val EVENT_FILE_LOADED = 8
    const val EVENT_END_FILE = 7
    const val EVENT_PROPERTY_CHANGE = 22

    // mpv_render_param_type
    const val RENDER_PARAM_INVALID = 0
    const val RENDER_PARAM_API_TYPE = 1
    const val RENDER_PARAM_OPENGL_INIT_PARAMS = 2
    const val RENDER_PARAM_OPENGL_FBO = 3
    const val RENDER_PARAM_FLIP_Y = 4
    const val RENDER_PARAM_ADVANCED_CONTROL = 10
    /** Prevent mpv_render_context_render() from sleeping until the video presentation time. */
    const val RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12
    const val RENDER_PARAM_SKIP_RENDERING = 13

    // mpv_render_context_update() bitmask
    const val RENDER_UPDATE_FRAME = 1L

    // reply_userdata ids we use for observed properties
    const val OBS_SUB_TEXT = 1L
    const val OBS_WIDTH = 2L
    const val OBS_HEIGHT = 3L
    const val OBS_PAUSE = 4L
    const val OBS_TITLE = 5L
    /** Selected subtitle track id (sid); used to refresh text/native subtitle mode. */
    const val OBS_SID = 6L
    const val OBS_TIME_POS = 7L
    const val OBS_DURATION = 8L
    const val OBS_EOF_REACHED = 9L
}
