package moe.rakka.mpvcraft.mpv

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference
import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.render.GlStateGuard
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL33C
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the libmpv handle and its OpenGL render context.
 *
 * Threading contract, do not break it:
 *   - [init], [renderFrame] and [shutdown] MUST run on the Minecraft render thread, because
 *     they touch the OpenGL context.
 *   - the event loop runs on its own daemon thread and only ever writes
 *     @Volatile fields. It never calls OpenGL.
 */
object MpvPlayer {

    private const val MPV_STRING_ENCODING = "UTF-8"

    private var lib: MpvLibrary? = null
    private var handle: Pointer? = null
    private var renderCtx: Pointer? = null
    private var ownerGlContext: Long = 0L

    @Volatile var available: Boolean = false; private set
    @Volatile var failureReason: String? = null; private set
    @Volatile var hasFile: Boolean = false; private set
    /** Resolver executable selected for mpv's ytdl hook, if one was found. */
    @Volatile var ytDlpExecutable: String? = null; private set

    /** Current subtitle line(s) as mpv would have drawn them. May contain '\n'. */
    @Volatile var subtitle: String = ""; private set

    @Volatile var videoWidth: Int = 0; private set
    @Volatile var videoHeight: Int = 0; private set
    @Volatile var paused: Boolean = false; private set
    @Volatile var title: String = ""; private set
    @Volatile var currentSource: String? = null; private set

    enum class SubtitlePresentation {
        /** No subtitle track is currently selected. */
        NONE,
        /** Text subtitle exposed through sub-text and drawn by MpvCraft. */
        DETACHED_TEXT,
        /** Bitmap subtitle (PGS/DVD/DVB/XSUB) rendered by mpv into the video frame. */
        NATIVE_IMAGE,
        /** Bitmap subtitle rendered by a synchronized transparent secondary libmpv core. */
        DETACHED_IMAGE,
    }

    @Volatile var subtitlePresentation: SubtitlePresentation = SubtitlePresentation.NONE
        private set

    val usesNativeImageSubtitles: Boolean
        get() = subtitlePresentation == SubtitlePresentation.NATIVE_IMAGE

    val usesDetachedImageSubtitles: Boolean
        get() = subtitlePresentation == SubtitlePresentation.DETACHED_IMAGE

    val usesImageSubtitles: Boolean
        get() = usesNativeImageSubtitles || usesDetachedImageSubtitles

    private val frameReady = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val subtitlePresentationDirty = AtomicBoolean(true)
    private var appliedNativeSubVisibility: Boolean? = null
    private var appliedSubEnabled: Boolean? = null
    private var appliedSubAttached: Boolean? = null
    private var lastDetachedClockSyncNanos: Long = 0L

    /**
     * Native callbacks must stay strongly reachable for as long as libmpv can
     * call them. If the GC eats these you get a native crash with no stack.
     */
    private val procAddressCb = MpvLibrary.GetProcAddress { _, name ->
        val addr = GLFW.glfwGetProcAddress(name)
        if (addr == 0L) null else Pointer(addr)
    }

    private val updateCb = MpvLibrary.UpdateCallback { frameReady.set(true) }

    // Long lived native memory, allocated once and reused every frame.
    private var apiTypeMem: Memory? = null
    private var advancedMem: Memory? = null
    private var glInitParams: MpvOpenGLInitParams? = null
    private var createParams: Array<MpvRenderParam>? = null

    private var fboStruct: MpvOpenGLFbo? = null
    private var flipYMem: Memory? = null
    /** 0 = never let libmpv sleep the Minecraft render thread until a video PTS. */
    private var blockForTargetTimeMem: Memory? = null
    private var renderParams: Array<MpvRenderParam>? = null

    private var eventThread: Thread? = null

    /** Flip the image vertically. MC draws y-down, GL is y-up, so this is normally true. */
    var flipY: Boolean = true

    /**
     * Hardware decoding mode, passed straight to mpv.
     *
     * Defaults to off. On Windows the GL interop paths (d3d11va, dxva2) expect an
     * ANGLE or EGL context, and Minecraft runs on plain desktop WGL, so asking for
     * hardware decoding there tends to produce exactly the kind of texture-format
     * error and later crash this mod was hitting. Software decoding is fine up to
     * 1080p. If you want to experiment, "d3d11va-copy" is the safe one to try: it
     * decodes on the GPU and copies back, so no interop is involved.
     */
    var hwdec: String = "no"

    // -------------------------------------------------------------------

    /** Safe to call repeatedly. Returns true once mpv is usable. */
    fun init(): Boolean {
        if (available) return true
        if (failureReason != null) return false

        val errors = mutableListOf<String>()
        val library = MpvLibrary.load(errors)
        if (library == null) {
            fail("libmpv not found. Tried:\n  " + errors.joinToString("\n  "))
            return false
        }
        lib = library

        val h = library.mpv_create()
        if (h == null) {
            fail("mpv_create() returned NULL")
            return false
        }

        // vo=libmpv is what enables the render API. Without it mpv opens its own window.
        library.mpv_set_option_string(h, "vo", "libmpv")
        library.mpv_set_option_string(h, "hwdec", hwdec)
        library.mpv_set_option_string(h, "terminal", "no")
        library.mpv_set_option_string(h, "idle", "yes")
        library.mpv_set_option_string(h, "keep-open", "yes")
        library.mpv_set_option_string(h, "osc", "no")
        library.mpv_set_option_string(h, "input-default-bindings", "no")

        // Web-page URLs are not media streams by themselves. mpv's ytdl hook asks
        // yt-dlp to resolve supported pages into the actual HLS/DASH/media URLs.
        // Direct http(s) media URLs still bypass yt-dlp and are opened normally.
        library.mpv_set_option_string(h, "ytdl", "yes")
        library.mpv_set_option_string(h, "ytdl-format", "bestvideo+bestaudio/best")
        ytDlpExecutable = locateYtDlp()
        val ytdlScriptOptions = buildList {
            add("ytdl_hook-try_ytdl_first=yes")
            ytDlpExecutable?.let { add("ytdl_hook-ytdl_path=$it") }
        }.joinToString(",")
        library.mpv_set_option_string(h, "script-opts", ytdlScriptOptions)
        ytDlpExecutable?.let { MpvCraft.logger.info("yt-dlp resolver: $it") }

        // Allow the UI/command volume control to use the requested 0..200% range.
        library.mpv_set_option_string(h, "volume-max", "200")
        // libmpv normally wakes the render callback ahead of the presentation time and
        // mpv_render_context_render() then sleeps until that PTS. Doing that on the
        // Minecraft render thread hard-caps the whole game to the video FPS. A zero
        // timing offset asks mpv to wake us at presentation time instead. The explicit
        // BLOCK_FOR_TARGET_TIME=0 render parameter below is the second half of the
        // guarantee: the game thread must never be used as mpv's frame timer.
        library.mpv_set_option_string(h, "video-timing-offset", "0")
        // We draw the subtitles ourselves, so mpv must not burn them into the frame.
        library.mpv_set_option_string(h, "sub-visibility", "no")

        // Optional native-side diagnostics without making normal runs noisy:
        //   -Dmpvcraft.mpvLog=run/mpv.log
        // This must be configured before mpv_initialize().
        System.getProperty("mpvcraft.mpvLog")
            ?.takeIf { it.isNotBlank() }
            ?.let { logPath ->
                library.mpv_set_option_string(h, "msg-level", "all=debug")
                library.mpv_set_option_string(h, "log-file", logPath)
            }

        val rc = library.mpv_initialize(h)
        if (rc < 0) {
            library.mpv_terminate_destroy(h)
            lib = null
            fail("mpv_initialize failed: ${library.mpv_error_string(rc)}")
            return false
        }
        handle = h

        val currentGlContext = GLFW.glfwGetCurrentContext()
        if (currentGlContext == 0L) {
            handle = null
            library.mpv_terminate_destroy(h)
            lib = null
            fail("Cannot create libmpv OpenGL renderer: no OpenGL context is current")
            return false
        }
        ownerGlContext = currentGlContext

        // mpv's OpenGL API requires a near-default GL state even during
        // mpv_render_context_create(). Minecraft normally has many bindings active,
        // so isolate initialization just like an ordinary video frame.
        if (!GlStateGuard.guarded { createRenderContext(library, h) }) {
            ownerGlContext = 0L
            handle = null
            library.mpv_terminate_destroy(h)
            lib = null
            return false
        }

        library.mpv_observe_property(h, Mpv.OBS_SUB_TEXT, "sub-text", Mpv.FORMAT_STRING)
        library.mpv_observe_property(h, Mpv.OBS_WIDTH, "dwidth", Mpv.FORMAT_INT64)
        library.mpv_observe_property(h, Mpv.OBS_HEIGHT, "dheight", Mpv.FORMAT_INT64)
        library.mpv_observe_property(h, Mpv.OBS_PAUSE, "pause", Mpv.FORMAT_FLAG)
        library.mpv_observe_property(h, Mpv.OBS_TITLE, "media-title", Mpv.FORMAT_STRING)
        library.mpv_observe_property(h, Mpv.OBS_SID, "sid", Mpv.FORMAT_STRING)

        stopping.set(false)
        startEventLoop(library, h)

        available = true
        MpvCraft.logger.info("libmpv initialized")
        return true
    }

    private fun createRenderContext(library: MpvLibrary, h: Pointer): Boolean {
        val api = Memory(7).also { it.setString(0, "opengl") }
        apiTypeMem = api

        val glInit = MpvOpenGLInitParams().also {
            it.get_proc_address = procAddressCb
            it.get_proc_address_ctx = null
            it.write()
        }
        glInitParams = glInit

        // Advanced control stays off. Enabling it is a promise that the render
        // thread never waits on ordinary libmpv API work; this integration also
        // issues normal player commands from Minecraft's client/render thread and
        // does not satisfy that stronger contract. OpenGL itself still stays inside
        // the mpv_render_* calls made with the owning context current.
        val advanced = Memory(4).also { it.setInt(0, 0) }
        advancedMem = advanced

        @Suppress("UNCHECKED_CAST")
        val params = MpvRenderParam().toArray(4) as Array<MpvRenderParam>
        params[0].type = Mpv.RENDER_PARAM_API_TYPE
        params[0].data = api
        params[1].type = Mpv.RENDER_PARAM_OPENGL_INIT_PARAMS
        params[1].data = glInit.pointer
        params[2].type = Mpv.RENDER_PARAM_ADVANCED_CONTROL
        params[2].data = advanced
        params[3].type = Mpv.RENDER_PARAM_INVALID
        params[3].data = null
        params.forEach { it.write() }
        createParams = params

        val out = PointerByReference()
        val rc = library.mpv_render_context_create(out, h, params[0].pointer)
        if (rc < 0) {
            fail("mpv_render_context_create failed: ${library.mpv_error_string(rc)}")
            return false
        }
        val ctx = out.value
        renderCtx = ctx
        library.mpv_render_context_set_update_callback(ctx, updateCb, null)

        // Reusable per-frame render params.
        fboStruct = MpvOpenGLFbo()
        flipYMem = Memory(4)
        blockForTargetTimeMem = Memory(4).also { it.setInt(0, 0) }

        @Suppress("UNCHECKED_CAST")
        val rp = MpvRenderParam().toArray(4) as Array<MpvRenderParam>
        rp[0].type = Mpv.RENDER_PARAM_OPENGL_FBO
        rp[1].type = Mpv.RENDER_PARAM_FLIP_Y
        rp[2].type = Mpv.RENDER_PARAM_BLOCK_FOR_TARGET_TIME
        rp[2].data = blockForTargetTimeMem
        rp[3].type = Mpv.RENDER_PARAM_INVALID
        renderParams = rp
        return true
    }

    private fun startEventLoop(library: MpvLibrary, h: Pointer) {
        val t = Thread({
            while (!stopping.get()) {
                val evPtr = try {
                    library.mpv_wait_event(h, -1.0)
                } catch (t: Throwable) {
                    if (!stopping.get()) MpvCraft.logger.error("mpv event loop died", t)
                    return@Thread
                } ?: continue

                // mpv_wakeup() returns EVENT_NONE. Check the stop flag before
                // touching the event so shutdown can join this thread before the
                // native handle is destroyed.
                if (stopping.get()) return@Thread

                val ev = MpvEvent(evPtr).also { it.read() }
                when (ev.event_id) {
                    Mpv.EVENT_NONE -> Unit
                    Mpv.EVENT_SHUTDOWN -> return@Thread
                    Mpv.EVENT_FILE_LOADED -> {
                        hasFile = true
                        subtitlePresentationDirty.set(true)
                    }
                    Mpv.EVENT_END_FILE -> {
                        hasFile = false
                        subtitle = ""
                        subtitlePresentation = SubtitlePresentation.NONE
                        subtitlePresentationDirty.set(true)
                        MpvBitmapSubtitlePlayer.deactivate()
                    }
                    Mpv.EVENT_PROPERTY_CHANGE -> ev.data?.let { handleProperty(ev.reply_userdata, it) }
                }
            }
        }, "mpvcraft-events")
        t.isDaemon = true
        t.start()
        eventThread = t
    }

    private fun handleProperty(id: Long, dataPtr: Pointer) {
        val prop = MpvEventProperty(dataPtr).also { it.read() }
        val d = prop.data ?: run {
            // property became unavailable
            if (id == Mpv.OBS_SUB_TEXT) subtitle = ""
            return
        }
        when (prop.format) {
            // For STRING the payload is a char**, so one extra dereference.
            Mpv.FORMAT_STRING -> {
                val str = d.getPointer(0)?.getString(0, MPV_STRING_ENCODING) ?: ""
                when (id) {
                    Mpv.OBS_SUB_TEXT -> subtitle = str
                    Mpv.OBS_TITLE -> title = str
                    Mpv.OBS_SID -> subtitlePresentationDirty.set(true)
                }
            }
            Mpv.FORMAT_INT64 -> {
                val v = d.getLong(0).toInt()
                when (id) {
                    Mpv.OBS_WIDTH -> if (v > 0) videoWidth = v
                    Mpv.OBS_HEIGHT -> if (v > 0) videoHeight = v
                }
            }
            Mpv.FORMAT_FLAG -> {
                val v = d.getInt(0) != 0
                if (id == Mpv.OBS_PAUSE) paused = v
            }
        }
    }

    // -------------------------------------------------------------------

    /** Cheap, GL-free check used by the PIP renderer on every Minecraft frame. */
    fun hasPendingRedraw(): Boolean = frameReady.get()

    /**
     * Consumes an mpv update notification. Render thread only, and call this from
     * inside [GlStateGuard]: mpv_render_context_update() is part of the render API
     * and is allowed to touch renderer resources even though ADVANCED_CONTROL is off.
     */
    fun consumePendingRedraw(): Boolean {
        val ctx = renderCtx ?: return false
        val library = lib ?: return false
        if (!hasOwningGlContext()) return false

        // If another callback races this update it flips frameReady back to true,
        // so the following Minecraft frame will consume the next notification.
        if (!frameReady.getAndSet(false)) return false
        library.mpv_render_context_update(ctx)
        return true
    }

    /** Framebuffer mpv draws into. Ours, not Minecraft's. */
    var targetFbo: Int = 0; private set
    private var targetTex: Int = 0
    var targetWidth: Int = 0; private set
    var targetHeight: Int = 0; private set

    /**
     * Allocates or resizes the offscreen surface mpv renders into.
     *
     * We deliberately do not hand mpv a Minecraft framebuffer. Minecraft's PIP
     * target carries a depth attachment and an internal format we do not control,
     * and mpv assumes it owns the target for the duration of the frame. Owning our
     * own RGBA8 colour-only FBO removes both unknowns, and the result gets blitted
     * across afterwards, which costs one GPU-side copy and nothing in RAM.
     */
    private fun ensureTarget(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (targetFbo != 0 && targetWidth == width && targetHeight == height) return true

        releaseTarget()

        // A non-zero GL_PIXEL_UNPACK_BUFFER changes a null TexImage pointer into
        // an offset inside that PBO. Minecraft can legitimately leave one bound,
        // so make this allocation independent of caller state as well as the guard.
        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0)

        val tex = GL33C.glGenTextures()
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, tex)
        GL33C.glTexImage2D(
            GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
            GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?
        )
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE)

        val fbo = GL33C.glGenFramebuffers()
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo)
        GL33C.glFramebufferTexture2D(
            GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, tex, 0
        )

        val status = GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER)
        if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            MpvCraft.logger.error("mpv target framebuffer incomplete: 0x${status.toString(16)}")
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0)
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0)
            GL33C.glDeleteFramebuffers(fbo)
            GL33C.glDeleteTextures(tex)
            return false
        }

        // mpv_render_context_render() expects default-ish incoming state and will
        // bind the FBO supplied in MPV_RENDER_PARAM_OPENGL_FBO itself.
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0)
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0)

        targetFbo = fbo
        targetTex = tex
        targetWidth = width
        targetHeight = height
        return true
    }

    private fun releaseTarget() {
        if (targetFbo != 0) GL33C.glDeleteFramebuffers(targetFbo)
        if (targetTex != 0) GL33C.glDeleteTextures(targetTex)
        targetFbo = 0
        targetTex = 0
        targetWidth = 0
        targetHeight = 0
    }

    /**
     * Draws the current frame into our own framebuffer. Render thread only.
     *
     * The caller must wrap this in GlStateGuard: mpv rebinds programs, VAOs and
     * textures without restoring them, and Minecraft's cached GL state does not
     * survive that.
     *
     * Returns true if [targetFbo] now holds a frame.
     */
    fun renderFrame(width: Int, height: Int): Boolean {
        val lib = lib ?: return false
        val ctx = renderCtx ?: return false
        if (!hasOwningGlContext()) {
            MpvCraft.logger.error("Refusing libmpv render: Minecraft's owning OpenGL context is not current")
            return false
        }
        val fboS = fboStruct ?: return false
        val flip = flipYMem ?: return false
        val noTimingWait = blockForTargetTimeMem ?: return false
        val rp = renderParams ?: return false
        if (!ensureTarget(width, height)) return false

        fboS.fbo = targetFbo
        fboS.w = width
        fboS.h = height
        fboS.internal_format = GL33C.GL_RGBA8
        fboS.write()

        flip.setInt(0, if (flipY) 1 else 0)

        rp[0].data = fboS.pointer
        rp[1].data = flip
        rp[2].data = noTimingWait
        rp[3].data = null
        rp.forEach { it.write() }

        // Do not pre-bind the target here. libmpv owns GL while render() runs and
        // binds the FBO described above itself. Entering with our FBO/viewport
        // already active violates the render_gl.h state contract.
        val rc = lib.mpv_render_context_render(ctx, rp[0].pointer)
        if (rc < 0) {
            MpvCraft.logger.warn("mpv render failed: ${lib.mpv_error_string(rc)}")
            return false
        }
        return true
    }

    // -------------------------------------------------------------------

    fun command(vararg args: String) {
        val lib = lib ?: return
        val h = handle ?: return
        lib.mpv_command(h, StringArray(args, MPV_STRING_ENCODING))
    }

    /**
     * Reads any mpv property as a string. Works for sub-properties too, which is
     * how track enumeration below avoids having to parse mpv's node format.
     * The returned native string is owned by mpv, hence the mpv_free.
     */
    fun getProperty(name: String): String? {
        val lib = lib ?: return null
        val h = handle ?: return null
        val p = lib.mpv_get_property_string(h, name) ?: return null
        val s = try { p.getString(0, MPV_STRING_ENCODING) } finally { lib.mpv_free(p) }
        return s
    }

    /**
     * Keeps subtitle rendering on the correct path for the selected track.
     *
     * Text tracks are exposed through `sub-text` and drawn by MpvCraft. Bitmap
     * tracks (PGS/VobSub/DVB/XSUB) have two paths:
     *  - attached: mpv composites them into the primary video FBO;
     *  - detached: a second synchronized libmpv core renders only the bitmap
     *    subtitle layer to a transparent FBO, keeping the original PGS palette,
     *    timing and placement without OCR or a second video decode.
     *
     * The detached image path is deliberately local-media first. Webpage URLs can
     * resolve to different internal track ids on a second ytdl session, so image
     * tracks on those sources safely stay attached instead of silently desyncing.
     */
    fun syncSubtitlePresentation(enabled: Boolean, attached: Boolean, force: Boolean = false) {
        if (!available || !hasFile) {
            subtitlePresentation = SubtitlePresentation.NONE
            MpvBitmapSubtitlePlayer.deactivate()
            return
        }

        // A helper failure is discovered lazily from the render thread. Force one
        // re-evaluation so the next frame falls back to native attached subtitles.
        if (subtitlePresentation == SubtitlePresentation.DETACHED_IMAGE &&
            !MpvBitmapSubtitlePlayer.canRenderCurrentSource
        ) {
            subtitlePresentationDirty.set(true)
        }

        val configChanged = appliedSubEnabled != enabled || appliedSubAttached != attached
        if (!force && !subtitlePresentationDirty.get() && !configChanged) {
            if (subtitlePresentation == SubtitlePresentation.DETACHED_IMAGE) syncDetachedImageClock()
            return
        }
        subtitlePresentationDirty.set(false)
        appliedSubEnabled = enabled
        appliedSubAttached = attached

        val codec = getProperty("current-tracks/sub/codec").orEmpty()
        val sid = getProperty("current-tracks/sub/id")?.toIntOrNull()
        val image = isImageSubtitleCodec(codec)
        val source = currentSource
        val externalSubtitle = selectedExternalSubtitleFilename()
        val externalSupported = externalSubtitle == null || MpvBitmapSubtitlePlayer.supportsSource(externalSubtitle)
        val detachedImageAllowed = image && !attached && enabled && sid != null &&
            MpvBitmapSubtitlePlayer.supportsSource(source) && externalSupported &&
            MpvBitmapSubtitlePlayer.canRenderCurrentSource

        subtitlePresentation = when {
            codec.isBlank() -> SubtitlePresentation.NONE
            image && detachedImageAllowed -> SubtitlePresentation.DETACHED_IMAGE
            image -> SubtitlePresentation.NATIVE_IMAGE
            else -> SubtitlePresentation.DETACHED_TEXT
        }

        if (subtitlePresentation == SubtitlePresentation.DETACHED_IMAGE) {
            MpvBitmapSubtitlePlayer.configure(source, sid, enabled = true, externalSubtitle = externalSubtitle)
            syncDetachedImageClock(force = true)
        } else {
            MpvBitmapSubtitlePlayer.deactivate()
        }

        // Only the native-image path enters the primary video output. Text and
        // detached-image subtitles remain hidden there so they cannot be doubled.
        val nativeVisible = enabled && subtitlePresentation == SubtitlePresentation.NATIVE_IMAGE
        if (appliedNativeSubVisibility != nativeVisible || force || configChanged) {
            setProperty("sub-visibility", if (nativeVisible) "yes" else "no")
            appliedNativeSubVisibility = nativeVisible
        }

        if (image) {
            // sub-text is empty for image subtitles; remove stale text immediately.
            subtitle = ""
        }
    }

    private fun selectedExternalSubtitleFilename(): String? {
        val count = getProperty("track-list/count")?.toIntOrNull() ?: return null
        for (i in 0 until count) {
            if (getProperty("track-list/$i/type") != "sub") continue
            if (getProperty("track-list/$i/selected") != "yes") continue
            return getProperty("track-list/$i/external-filename")?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun syncDetachedImageClock(force: Boolean = false) {
        if (subtitlePresentation != SubtitlePresentation.DETACHED_IMAGE) return
        val now = System.nanoTime()
        if (!force && now - lastDetachedClockSyncNanos < 75_000_000L) return
        lastDetachedClockSyncNanos = now

        val time = getProperty("time-pos")?.toDoubleOrNull() ?: return
        val speed = getProperty("speed")?.toDoubleOrNull() ?: 1.0
        val delay = getProperty("sub-delay")?.toDoubleOrNull() ?: 0.0
        MpvBitmapSubtitlePlayer.updateClock(time, paused, speed, delay)
    }

    fun setSubtitlesEnabled(enabled: Boolean, attached: Boolean) {
        syncSubtitlePresentation(enabled, attached, force = true)
    }

    private fun isImageSubtitleCodec(codec: String): Boolean {
        val c = codec.lowercase()
        return c == "hdmv_pgs_subtitle" ||
            c == "dvd_subtitle" ||
            c == "dvb_subtitle" ||
            c == "xsub" ||
            c.contains("pgs")
    }

    data class Track(
        val id: Int,
        val type: String,
        val title: String,
        val lang: String,
        val codec: String,
        val selected: Boolean,
    ) {
        val imageSubtitle: Boolean
            get() {
                val c = codec.lowercase()
                return type == "sub" && (
                    c == "hdmv_pgs_subtitle" || c == "dvd_subtitle" ||
                    c == "dvb_subtitle" || c == "xsub" || c.contains("pgs")
                )
            }

        /** Something readable for a button label. */
        fun label(): String {
            val parts = mutableListOf<String>()
            if (lang.isNotBlank()) parts += lang
            if (title.isNotBlank()) parts += title
            if (parts.isEmpty() && codec.isNotBlank()) parts += codec
            if (parts.isEmpty()) parts += "track $id"
            val base = parts.joinToString(" - ")
            return if (imageSubtitle) "$base [image]" else base
        }
    }

    /**
     * Enumerates the tracks of the loaded file.
     *
     * Queried through the indexed track-list/N/... sub-properties rather than
     * reading track-list as a node, so everything stays plain strings and there
     * is no serialization format to depend on.
     */
    fun tracks(): List<Track> {
        val count = getProperty("track-list/count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val id = getProperty("track-list/$i/id")?.toIntOrNull() ?: return@mapNotNull null
            Track(
                id = id,
                type = getProperty("track-list/$i/type") ?: "?",
                title = getProperty("track-list/$i/title").orEmpty(),
                lang = getProperty("track-list/$i/lang").orEmpty(),
                codec = getProperty("track-list/$i/codec").orEmpty(),
                selected = getProperty("track-list/$i/selected") == "yes",
            )
        }
    }

    /** [type] is mpv's own naming: "sub", "audio" or "video". [id] null disables it. */
    fun selectTrack(type: String, id: Int?) {
        val prop = when (type) {
            "sub" -> "sid"
            "audio" -> "aid"
            else -> "vid"
        }
        setProperty(prop, id?.toString() ?: "no")
        if (type == "sub") subtitlePresentationDirty.set(true)
    }

    data class Chapter(
        val index: Int,
        val title: String,
        val time: Double,
    ) {
        fun label(): String {
            val total = time.coerceAtLeast(0.0).toInt()
            val minutes = total / 60
            val seconds = total % 60
            val name = title.ifBlank { "Chapter ${index + 1}" }
            return "%02d:%02d  %s".format(minutes, seconds, name)
        }
    }

    /** Enumerates chapter metadata exposed by mpv's chapter-list sub-properties. */
    fun chapters(): List<Chapter> {
        val count = getProperty("chapter-list/count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val time = getProperty("chapter-list/$i/time")?.toDoubleOrNull() ?: return@mapNotNull null
            Chapter(
                index = i,
                title = getProperty("chapter-list/$i/title").orEmpty(),
                time = time,
            )
        }
    }

    fun currentChapterIndex(): Int = getProperty("chapter")?.toIntOrNull() ?: -1

    fun selectChapter(index: Int) {
        if (index >= 0) setProperty("chapter", index.toString())
    }

    fun previousChapter() = command("add", "chapter", "-1")
    fun nextChapter() = command("add", "chapter", "1")

    /**
     * Finds the most likely chapter immediately after an opening/intro.
     *
     * Anime releases often name the opening chapter explicitly. If they do not,
     * the conventional fallback requested by MpvCraft is the start of chapter 4
     * (index 3), i.e. immediately after the third chapter. Never seek backwards.
     */
    fun introTarget(chapterList: List<Chapter> = chapters()): Chapter? {
        if (chapterList.isEmpty()) return null

        val namedOpening = chapterList.indexOfFirst { chapter ->
            val t = chapter.title.trim().lowercase()
            t == "op" ||
                t.startsWith("op ") ||
                t.contains("opening") ||
                t.contains("intro") ||
                t.contains("オープニング")
        }

        val targetIndex = when {
            namedOpening >= 0 && namedOpening + 1 < chapterList.size -> namedOpening + 1
            chapterList.size >= 4 -> 3
            else -> return null
        }
        return chapterList[targetIndex]
    }

    /** Seeks past the detected intro and returns the destination, or null if no seek was made. */
    fun skipIntro(): Chapter? {
        val list = chapters()
        val target = introTarget(list) ?: return null
        val now = getProperty("time-pos")?.toDoubleOrNull() ?: 0.0
        if (now >= target.time - 0.25) return null
        selectChapter(target.index)
        return target
    }

    fun volume(): Int = getProperty("volume")?.toFloatOrNull()?.toInt() ?: 0

    fun setProperty(name: String, value: String) {
        val lib = lib ?: return
        val h = handle ?: return
        lib.mpv_set_property_string(h, name, value)
    }

    fun load(path: String) {
        if (!init()) return
        if (looksLikeWebPage(path) && ytDlpExecutable == null) {
            MpvCraft.logger.warn(
                "Opening a web-page URL without a detected yt-dlp executable. " +
                    "Direct media URLs may still work, but page extraction will usually fail: $path"
            )
        }
        MpvBitmapSubtitlePlayer.deactivate()
        currentSource = path
        hasFile = false
        subtitle = ""
        subtitlePresentation = SubtitlePresentation.NONE
        subtitlePresentationDirty.set(true)
        appliedNativeSubVisibility = null
        command("loadfile", path, "replace")
    }

    fun togglePause() = command("cycle", "pause")
    fun stop() {
        command("stop")
        MpvBitmapSubtitlePlayer.deactivate()
        currentSource = null
        hasFile = false
        subtitle = ""
        subtitlePresentation = SubtitlePresentation.NONE
        subtitlePresentationDirty.set(true)
        appliedNativeSubVisibility = null
    }

    fun seek(seconds: Int) = command("seek", seconds.toString(), "relative")
    fun cycleSubTrack() = command("cycle", "sid")
    fun cycleAudioTrack() = command("cycle", "aid")
    fun setVolume(v: Int) = setProperty("volume", v.coerceIn(0, 200).toString())


    private fun locateYtDlp(): String? {
        val configured = sequenceOf(
            System.getProperty("mpvcraft.ytdlp"),
            runCatching { MpvCraft.config.ytDlpPath }.getOrNull(),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .map(::File)
            .firstOrNull { it.isFile }
        if (configured != null) return configured.absolutePath

        val names = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("yt-dlp.exe", "yt-dlp_x86.exe")
        } else {
            listOf("yt-dlp")
        }

        val gameDir = MpvCraft.mc.gameDirectory
        val localCandidates = buildList {
            names.forEach { name ->
                add(File(gameDir, name))
                add(File(gameDir, "tools/$name"))
                add(File(gameDir, "mpvcraft/$name"))
            }
        }
        localCandidates.firstOrNull { it.isFile }?.let { return it.absolutePath }

        val path = System.getenv("PATH").orEmpty()
        path.split(File.pathSeparatorChar).forEach { dir ->
            if (dir.isBlank()) return@forEach
            names.forEach { name ->
                val candidate = File(dir, name)
                if (candidate.isFile) return candidate.absolutePath
            }
        }
        return null
    }

    private fun looksLikeWebPage(value: String): Boolean {
        val lower = value.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val clean = lower.substringBefore('?').substringBefore('#')
        val direct = listOf(
            ".m3u8", ".mpd", ".mp4", ".mkv", ".webm", ".mov", ".avi",
            ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus",
        )
        return direct.none { clean.endsWith(it) }
    }

    /** Aspect ratio of the loaded video, 16/9 as a fallback. */
    fun aspect(): Float =
        if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else 16f / 9f

    fun shutdown() {
        val library = lib ?: return
        val h = handle

        // Stop new renders first, then wake and join the event thread. Destroying
        // the mpv_handle while mpv_wait_event() still owns it is a native UAF race.
        available = false
        stopping.set(true)
        if (h != null) library.mpv_wakeup(h)

        val t = eventThread
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        eventThread = null

        val ctx = renderCtx
        val hasOwnerContext = hasOwningGlContext()
        var renderContextFreed = ctx == null
        if (ctx != null) {
            if (hasOwnerContext) {
                GlStateGuard.guarded {
                    releaseTarget()
                    library.mpv_render_context_set_update_callback(ctx, null, null)
                    library.mpv_render_context_free(ctx)
                }
                renderContextFreed = true
            } else {
                // render.h requires the exact same GL context used at creation, not
                // merely any current context. Freeing with the wrong context is UB.
                MpvCraft.logger.error(
                    "Skipping libmpv OpenGL teardown because its owning GL context is not current"
                )
            }
        } else if (hasOwnerContext) {
            releaseTarget()
        }

        // The render context must be gone before the mpv core is destroyed. When
        // GL teardown had to be skipped above, leave the core allocated too; the
        // process is already exiting and the OS will reclaim it safely.
        if (renderContextFreed) {
            h?.let { library.mpv_terminate_destroy(it) }
        }

        renderCtx = null
        ownerGlContext = 0L
        handle = null
        lib = null
        frameReady.set(false)
        apiTypeMem = null
        advancedMem = null
        glInitParams = null
        createParams = null
        fboStruct = null
        flipYMem = null
        blockForTargetTimeMem = null
        renderParams = null
        currentSource = null
        hasFile = false
        subtitle = ""
        subtitlePresentation = SubtitlePresentation.NONE
        subtitlePresentationDirty.set(true)
        appliedNativeSubVisibility = null
        appliedSubEnabled = null
        appliedSubAttached = null
    }

    private fun hasOwningGlContext(): Boolean =
        ownerGlContext != 0L && GLFW.glfwGetCurrentContext() == ownerGlContext

    private fun fail(msg: String) {
        failureReason = msg
        available = false
        MpvCraft.logger.error(msg)
    }
}
