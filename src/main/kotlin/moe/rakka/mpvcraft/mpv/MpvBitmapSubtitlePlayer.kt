package moe.rakka.mpvcraft.mpv

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference
import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.render.GlStateGuard
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL33C
import org.lwjgl.system.MemoryStack
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Secondary, subtitle-only libmpv core used for bitmap subtitles.
 *
 * Why a second core?
 * ------------------
 * mpv deliberately exposes text subtitles through `sub-text`, but PGS/VobSub/
 * DVB/XSUB are already decoded bitmap overlays and there is no public libmpv
 * API that returns their pixels. Rendering them through the primary core burns
 * them into the video surface, which makes a real detached HUD impossible.
 *
 * This object opens the same local media in a second libmpv core with video
 * disabled, audio routed to the realtime null AO, `force-window=immediate`, and
 * a transparent background. mpv therefore still owns demuxing, bitmap subtitle
 * decoding, timing and palette composition, but its render API output contains
 * only the subtitle/OSD layer. The result stays entirely on the GPU and is
 * composited by [moe.rakka.mpvcraft.render.MpvSubtitlePipRenderer].
 *
 * The null audio output is intentional: it provides a real playback clock while
 * avoiding a second audible stream or a second video decode. We periodically
 * slave this helper to the primary core (pause/speed/time/sub-delay) and perform
 * an exact correction only if drift grows beyond a small threshold or after a
 * seek. In normal playback the two cores simply advance together.
 *
 * Current scope is local media. Re-opening an extracted/ytdl webpage URL in a
 * second core can trigger a second resolver/network session and does not
 * guarantee identical track ids, so those sources safely fall back to mpv's
 * native attached bitmap subtitle path.
 */
object MpvBitmapSubtitlePlayer {

    private const val MPV_STRING_ENCODING = "UTF-8"
    private const val CLOCK_SYNC_INTERVAL_NS = 120_000_000L
    private const val DRIFT_SEEK_THRESHOLD = 0.16
    private const val LOAD_TIMEOUT_NS = 8_000_000_000L

    private var lib: MpvLibrary? = null
    private var handle: Pointer? = null
    private var renderCtx: Pointer? = null
    private var ownerGlContext: Long = 0L

    @Volatile private var initialized = false
    @Volatile private var permanentFailure: String? = null
    @Volatile private var sourceFailure: String? = null
    @Volatile private var fileLoaded = false

    @Volatile private var desiredEnabled = false
    @Volatile private var desiredSource: String? = null
    @Volatile private var desiredSid: Int? = null
    @Volatile private var desiredExternalSubtitle: String? = null
    @Volatile private var desiredTime = 0.0
    @Volatile private var desiredPaused = true
    @Volatile private var desiredSpeed = 1.0
    @Volatile private var desiredSubDelay = 0.0

    @Volatile private var requestedSource: String? = null
    private var appliedSid: Int? = null
    private var appliedExternalSubtitle: String? = null
    private var appliedPaused: Boolean? = null
    private var appliedSpeed: Double? = null
    private var appliedSubDelay: Double? = null
    private var requestedAtNanos = 0L
    private var lastClockSyncNanos = 0L
    private var needsExactSync = true

    private val frameReady = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var eventThread: Thread? = null

    // Native callback instances must be strongly reachable for the entire core lifetime.
    private val procAddressCb = MpvLibrary.GetProcAddress { _, name ->
        val addr = GLFW.glfwGetProcAddress(name)
        if (addr == 0L) null else Pointer(addr)
    }
    private val updateCb = MpvLibrary.UpdateCallback { frameReady.set(true) }

    // Long-lived render API memory.
    private var apiTypeMem: Memory? = null
    private var advancedMem: Memory? = null
    private var glInitParams: MpvOpenGLInitParams? = null
    private var createParams: Array<MpvRenderParam>? = null
    private var fboStruct: MpvOpenGLFbo? = null
    private var flipYMem: Memory? = null
    private var blockForTargetTimeMem: Memory? = null
    private var renderParams: Array<MpvRenderParam>? = null

    var targetFbo: Int = 0
        private set
    private var targetTex: Int = 0
    var targetWidth: Int = 0
        private set
    var targetHeight: Int = 0
        private set

    private var alphaValidated = false

    val failureReason: String?
        get() = sourceFailure ?: permanentFailure

    val canRenderCurrentSource: Boolean
        get() = permanentFailure == null && sourceFailure == null

    val active: Boolean
        get() = desiredEnabled && desiredSource != null && desiredSid != null && canRenderCurrentSource

    val readyForDisplay: Boolean
        get() = active && fileLoaded && requestedSource == desiredSource &&
            if (desiredExternalSubtitle != null) appliedExternalSubtitle == desiredExternalSubtitle
            else appliedSid == desiredSid

    /** Bitmap detaching intentionally avoids a second web resolver/network session. */
    fun supportsSource(source: String?): Boolean {
        val raw = source?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val lower = raw.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ytdl://")) return false
        if (lower.startsWith("bd://") || lower.startsWith("dvd://")) return false
        if (lower.startsWith("file://")) return true
        return runCatching {
            val direct = File(raw)
            direct.isFile || (!direct.isAbsolute && File(MpvCraft.mc.gameDirectory, raw).isFile)
        }.getOrDefault(false)
    }

    /**
     * Pure state update; safe from the client thread. Native work is deferred to
     * [prepareForRender] where Minecraft's owning GL context is known to be current.
     */
    fun configure(source: String?, sid: Int?, enabled: Boolean, externalSubtitle: String? = null) {
        val external = externalSubtitle?.takeIf { it.isNotBlank() }
        val externalSupported = external == null || supportsSource(external)
        val supported = enabled && sid != null && supportsSource(source) && externalSupported
        desiredEnabled = supported
        desiredSource = if (supported) source else null
        desiredSid = if (supported) sid else null
        desiredExternalSubtitle = if (supported) external else null

        if (!supported) {
            sourceFailure = null
            frameReady.set(false)
            return
        }

        if (requestedSource != source) {
            sourceFailure = null
            fileLoaded = false
            appliedSid = null
            appliedExternalSubtitle = null
            alphaValidated = false
            needsExactSync = true
        }
        frameReady.set(true)
    }

    /** Called by the primary player at a throttled cadence. */
    fun updateClock(time: Double, paused: Boolean, speed: Double, subDelay: Double) {
        if (!desiredEnabled) return
        val previous = desiredTime
        desiredTime = time.coerceAtLeast(0.0)
        desiredPaused = paused
        desiredSpeed = speed.coerceIn(0.01, 100.0)
        desiredSubDelay = subDelay
        // A discontinuity in the primary clock is almost certainly a seek.
        if (abs(desiredTime - previous) > 0.75) needsExactSync = true
    }

    /**
     * Render-thread entry point. Initializes/loads the helper lazily and applies
     * pending subtitle selection + clock synchronization.
     */
    fun prepareForRender(): Boolean {
        if (!active) return false
        if (!init()) return false

        val source = desiredSource ?: return false
        if (requestedSource != source) {
            requestedSource = source
            fileLoaded = false
            sourceFailure = null
            appliedSid = null
            appliedExternalSubtitle = null
            appliedPaused = null
            appliedSpeed = null
            appliedSubDelay = null
            alphaValidated = false
            needsExactSync = true
            requestedAtNanos = System.nanoTime()
            command("loadfile", source, "replace")
            frameReady.set(true)
            return false
        }

        if (!fileLoaded) {
            if (requestedAtNanos != 0L && System.nanoTime() - requestedAtNanos > LOAD_TIMEOUT_NS) {
                sourceFailure = "Detached bitmap subtitle helper timed out while opening the media"
                MpvCraft.logger.warn(sourceFailure)
            }
            return false
        }

        val sid = desiredSid ?: return false
        val external = desiredExternalSubtitle
        if (external != null) {
            if (appliedExternalSubtitle != external) {
                // External bitmap tracks added to the primary core do not exist in
                // this helper's track list. Add/select the same file explicitly.
                command("sub-add", external, "select")
                setProperty("sub-visibility", "yes")
                appliedExternalSubtitle = external
                appliedSid = sid // primary id kept only as a configuration token
                needsExactSync = true
                frameReady.set(true)
            }
        } else if (appliedSid != sid || appliedExternalSubtitle != null) {
            setProperty("sid", sid.toString())
            setProperty("sub-visibility", "yes")
            appliedSid = sid
            appliedExternalSubtitle = null
            needsExactSync = true
            frameReady.set(true)
        }

        syncClockOnRenderThread()
        return canRenderCurrentSource
    }

    private fun syncClockOnRenderThread() {
        val now = System.nanoTime()

        if (appliedPaused != desiredPaused) {
            setProperty("pause", if (desiredPaused) "yes" else "no")
            appliedPaused = desiredPaused
            frameReady.set(true)
        }
        if (appliedSpeed == null || abs(appliedSpeed!! - desiredSpeed) > 0.0001) {
            setProperty("speed", desiredSpeed.toString())
            appliedSpeed = desiredSpeed
        }
        if (appliedSubDelay == null || abs(appliedSubDelay!! - desiredSubDelay) > 0.0001) {
            setProperty("sub-delay", desiredSubDelay.toString())
            appliedSubDelay = desiredSubDelay
            frameReady.set(true)
        }

        if (!needsExactSync && now - lastClockSyncNanos < CLOCK_SYNC_INTERVAL_NS) return
        lastClockSyncNanos = now

        val helperTime = getProperty("time-pos")?.toDoubleOrNull()
        val drift = if (helperTime == null) Double.POSITIVE_INFINITY else abs(helperTime - desiredTime)
        if (needsExactSync || drift > DRIFT_SEEK_THRESHOLD) {
            // exact is important for PGS display-set boundaries immediately after a seek.
            command("seek", "%.6f".format(java.util.Locale.ROOT, desiredTime), "absolute+exact")
            needsExactSync = false
            frameReady.set(true)
        }
    }

    private fun init(): Boolean {
        if (initialized) return true
        if (permanentFailure != null) return false
        if (GLFW.glfwGetCurrentContext() == 0L) return false

        val errors = mutableListOf<String>()
        val library = MpvLibrary.load(errors)
        if (library == null) {
            permanentFailure = "Could not load libmpv for detached bitmap subtitles: ${errors.joinToString("; ")}"
            MpvCraft.logger.warn(permanentFailure)
            return false
        }
        lib = library

        val h = library.mpv_create()
        if (h == null) {
            permanentFailure = "mpv_create() failed for detached bitmap subtitles"
            lib = null
            return false
        }

        fun option(name: String, value: String): Int = library.mpv_set_option_string(h, name, value)

        option("vo", "libmpv")
        option("hwdec", "no")
        option("terminal", "no")
        option("idle", "yes")
        option("keep-open", "yes")
        option("osc", "no")
        option("osd-level", "0")
        option("osd-bar", "no")
        option("osd-on-seek", "no")
        option("input-default-bindings", "no")
        option("force-window", "immediate")
        option("vid", "no")
        option("audio-display", "no")
        // Keep a cheap real-time clock without producing a second audible stream.
        option("ao", "null")
        option("mute", "yes")
        option("volume", "0")
        option("pause", "yes")
        option("sub-visibility", "yes")
        option("video-timing-offset", "0")
        option("force-rgba-osd-rendering", "yes")
        option("blend-subtitles", "no")

        // mpv >= 0.38: retain alpha instead of painting an opaque background.
        // Older mpv accepted a color directly in --background and had --alpha.
        val backgroundRc = option("background", "none")
        if (backgroundRc < 0) {
            option("alpha", "yes")
            option("background", "#00000000")
        }
        option("background-color", "#00000000")

        // Keep URL options aligned with the primary core even though current
        // detached-image scope is local-only. This also makes file:// and future
        // direct-stream support unsurprising.
        option("ytdl", "yes")
        option("ytdl-format", "bestvideo+bestaudio/best")
        MpvPlayer.ytDlpExecutable?.let { path ->
            option("script-opts", "ytdl_hook-try_ytdl_first=yes,ytdl_hook-ytdl_path=$path")
        }

        val rc = library.mpv_initialize(h)
        if (rc < 0) {
            permanentFailure = "Detached bitmap subtitle mpv_initialize failed: ${library.mpv_error_string(rc)}"
            library.mpv_terminate_destroy(h)
            lib = null
            MpvCraft.logger.warn(permanentFailure)
            return false
        }
        handle = h

        ownerGlContext = GLFW.glfwGetCurrentContext()
        if (!GlStateGuard.guarded { createRenderContext(library, h) }) {
            handle = null
            ownerGlContext = 0L
            library.mpv_terminate_destroy(h)
            lib = null
            if (permanentFailure == null) permanentFailure = "Could not create subtitle-only libmpv OpenGL renderer"
            return false
        }

        stopping.set(false)
        startEventLoop(library, h)
        initialized = true
        MpvCraft.logger.info("Detached bitmap subtitle renderer initialized")
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
            permanentFailure = "Detached subtitle render context failed: ${library.mpv_error_string(rc)}"
            MpvCraft.logger.warn(permanentFailure)
            return false
        }
        val ctx = out.value
        renderCtx = ctx
        library.mpv_render_context_set_update_callback(ctx, updateCb, null)

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
                    if (!stopping.get()) MpvCraft.logger.warn("Detached subtitle mpv event loop died", t)
                    return@Thread
                } ?: continue
                if (stopping.get()) return@Thread

                val ev = MpvEvent(evPtr).also { it.read() }
                when (ev.event_id) {
                    Mpv.EVENT_NONE -> Unit
                    Mpv.EVENT_SHUTDOWN -> return@Thread
                    Mpv.EVENT_FILE_LOADED -> {
                        fileLoaded = true
                        sourceFailure = null
                        requestedAtNanos = 0L
                        appliedSid = null
                        appliedExternalSubtitle = null
                        needsExactSync = true
                        frameReady.set(true)
                    }
                    Mpv.EVENT_END_FILE -> {
                        if (desiredEnabled && requestedSource == desiredSource && !fileLoaded) {
                            sourceFailure = "Detached bitmap subtitle helper could not open this media"
                        }
                        fileLoaded = false
                        frameReady.set(true)
                    }
                }
            }
        }, "mpvcraft-bitmap-subs")
        t.isDaemon = true
        t.start()
        eventThread = t
    }

    fun hasPendingRedraw(): Boolean = frameReady.get()

    fun consumePendingRedraw(): Boolean {
        val ctx = renderCtx ?: return false
        val library = lib ?: return false
        if (!hasOwningGlContext()) return false
        if (!frameReady.getAndSet(false)) return false
        library.mpv_render_context_update(ctx)
        return true
    }

    private fun ensureTarget(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (targetFbo != 0 && targetWidth == width && targetHeight == height) return true
        releaseTarget()

        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0)
        val tex = GL33C.glGenTextures()
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, tex)
        GL33C.glTexImage2D(
            GL33C.GL_TEXTURE_2D,
            0,
            GL33C.GL_RGBA8,
            width,
            height,
            0,
            GL33C.GL_RGBA,
            GL33C.GL_UNSIGNED_BYTE,
            null as java.nio.ByteBuffer?,
        )
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE)

        val fbo = GL33C.glGenFramebuffers()
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo)
        GL33C.glFramebufferTexture2D(
            GL33C.GL_FRAMEBUFFER,
            GL33C.GL_COLOR_ATTACHMENT0,
            GL33C.GL_TEXTURE_2D,
            tex,
            0,
        )
        val status = GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER)
        if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0)
            GL33C.glDeleteFramebuffers(fbo)
            GL33C.glDeleteTextures(tex)
            sourceFailure = "Detached subtitle framebuffer incomplete: 0x${status.toString(16)}"
            MpvCraft.logger.warn(sourceFailure)
            return false
        }

        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0)
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0)
        targetFbo = fbo
        targetTex = tex
        targetWidth = width
        targetHeight = height
        alphaValidated = false
        return true
    }

    /** Render the transparent subtitle canvas into [targetFbo]. Render thread only. */
    fun renderFrame(width: Int, height: Int): Boolean {
        if (!prepareForRender()) return false
        val library = lib ?: return false
        val ctx = renderCtx ?: return false
        if (!hasOwningGlContext()) return false
        val fbo = fboStruct ?: return false
        val flip = flipYMem ?: return false
        val noWait = blockForTargetTimeMem ?: return false
        val params = renderParams ?: return false
        if (!ensureTarget(width, height)) return false

        // Transparent clear is important for areas untouched by mpv's subtitle OSD.
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, targetFbo)
        GL33C.glViewport(0, 0, width, height)
        GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
        MemoryStack.stackPush().use { stack ->
            GL33C.glClearBufferfv(GL33C.GL_COLOR, 0, stack.floats(0f, 0f, 0f, 0f))
        }

        fbo.fbo = targetFbo
        fbo.w = width
        fbo.h = height
        fbo.internal_format = GL33C.GL_RGBA8
        fbo.write()
        flip.setInt(0, if (MpvPlayer.flipY) 1 else 0)

        params[0].data = fbo.pointer
        params[1].data = flip
        params[2].data = noWait
        params[3].data = null
        params.forEach { it.write() }

        val rc = library.mpv_render_context_render(ctx, params[0].pointer)
        if (rc < 0) {
            sourceFailure = "Detached subtitle render failed: ${library.mpv_error_string(rc)}"
            MpvCraft.logger.warn(sourceFailure)
            return false
        }

        if (!alphaValidated) {
            if (!validateTransparentBackground()) return false
            alphaValidated = true
        }
        return true
    }

    /**
     * A transparent subtitle surface should have alpha ~= 0 in almost all corners.
     * This tiny one-time readback prevents an incompatible mpv build from placing a
     * giant opaque black rectangle over Minecraft; failure falls back to attached PGS.
     */
    private fun validateTransparentBackground(): Boolean {
        if (targetFbo == 0 || targetWidth < 2 || targetHeight < 2) return false
        GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, targetFbo)
        val sample = BufferUtils.createByteBuffer(4)
        val points = arrayOf(
            1 to 1,
            (targetWidth - 2) to 1,
            1 to (targetHeight - 2),
            (targetWidth - 2) to (targetHeight - 2),
        )
        var opaqueCorners = 0
        for ((x, y) in points) {
            sample.clear()
            GL33C.glReadPixels(x, y, 1, 1, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, sample)
            val alpha = sample.get(3).toInt() and 0xFF
            if (alpha > 24) opaqueCorners++
        }
        if (opaqueCorners >= 3) {
            sourceFailure =
                "This libmpv build cannot provide a transparent subtitle-only surface; using attached image subtitles"
            MpvCraft.logger.warn(sourceFailure)
            return false
        }
        return true
    }

    private fun command(vararg args: String) {
        val library = lib ?: return
        val h = handle ?: return
        library.mpv_command(h, StringArray(args, MPV_STRING_ENCODING))
    }

    private fun setProperty(name: String, value: String) {
        val library = lib ?: return
        val h = handle ?: return
        library.mpv_set_property_string(h, name, value)
    }

    private fun getProperty(name: String): String? {
        val library = lib ?: return null
        val h = handle ?: return null
        val p = library.mpv_get_property_string(h, name) ?: return null
        return try {
            p.getString(0, MPV_STRING_ENCODING)
        } finally {
            library.mpv_free(p)
        }
    }

    private fun releaseTarget() {
        if (targetFbo != 0) GL33C.glDeleteFramebuffers(targetFbo)
        if (targetTex != 0) GL33C.glDeleteTextures(targetTex)
        targetFbo = 0
        targetTex = 0
        targetWidth = 0
        targetHeight = 0
    }

    fun deactivate() {
        desiredEnabled = false
        desiredSource = null
        desiredSid = null
        desiredExternalSubtitle = null
        sourceFailure = null
        frameReady.set(false)
        if (initialized && handle != null) command("stop")
        requestedSource = null
        fileLoaded = false
        appliedSid = null
        appliedExternalSubtitle = null
        needsExactSync = true
    }

    fun shutdown() {
        val library = lib ?: return
        val h = handle
        desiredEnabled = false
        stopping.set(true)
        if (h != null) library.mpv_wakeup(h)
        eventThread?.let { t ->
            if (t !== Thread.currentThread()) {
                try {
                    t.join()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        eventThread = null

        val ctx = renderCtx
        val ownerCurrent = hasOwningGlContext()
        var renderFreed = ctx == null
        if (ctx != null && ownerCurrent) {
            GlStateGuard.guarded {
                releaseTarget()
                library.mpv_render_context_set_update_callback(ctx, null, null)
                library.mpv_render_context_free(ctx)
            }
            renderFreed = true
        } else if (ctx == null && ownerCurrent) {
            releaseTarget()
        }

        if (renderFreed) h?.let { library.mpv_terminate_destroy(it) }

        renderCtx = null
        handle = null
        lib = null
        ownerGlContext = 0L
        initialized = false
        fileLoaded = false
        frameReady.set(false)
        apiTypeMem = null
        advancedMem = null
        glInitParams = null
        createParams = null
        fboStruct = null
        flipYMem = null
        blockForTargetTimeMem = null
        renderParams = null
    }

    private fun hasOwningGlContext(): Boolean =
        ownerGlContext != 0L && GLFW.glfwGetCurrentContext() == ownerGlContext
}
