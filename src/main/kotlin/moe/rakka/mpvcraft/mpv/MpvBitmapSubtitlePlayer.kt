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
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
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
 * This object opens the same local media in a second libmpv core with its real
 * video disabled and audio routed to the realtime null AO. A generated fully
 * transparent PNG, matching the capped subtitle render canvas, is then selected as
 * an external video track. That detail is important: modern mpv's `background=none`
 * only preserves transparency when the current video frame actually has alpha.
 * With `vid=no` alone mpv can legitimately return an opaque black OSD canvas,
 * which is why the V5.6 path fell back to attached PGS on some Windows builds.
 * The transparent dummy video gives the renderer a real RGBA frame while mpv
 * continues to own PGS/VobSub decoding, timing, palette and positioning.
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
    // PGS is event-like content. Rendering a second 1080p/4K libmpv surface at
    // Minecraft's frame rate wastes a lot of GPU bandwidth for no visible gain.
    // The helper is intentionally capped to 20 Hz; subtitle changes still land
    // within 50 ms while the game remains essentially unaffected.
    private const val MIN_RENDER_INTERVAL_NS = 50_000_000L
    private const val MAX_RENDER_WIDTH = 960
    private const val MAX_RENDER_HEIGHT = 540
    private const val MAX_SCAN_WIDTH = 320
    private const val MAX_SCAN_HEIGHT = 180
    private const val ALPHA_THRESHOLD = 8

    /** Tight alpha bounds of the currently visible bitmap subtitle, top-left based. */
    data class SubtitleCrop(val x: Int, val y: Int, val width: Int, val height: Int)

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
    @Volatile private var desiredVideoWidth = 0
    @Volatile private var desiredVideoHeight = 0
    @Volatile private var desiredTime = 0.0
    @Volatile private var desiredPaused = true
    @Volatile private var desiredSpeed = 1.0
    @Volatile private var desiredSubDelay = 0.0

    @Volatile private var requestedSource: String? = null
    private var appliedSid: Int? = null
    private var appliedExternalSubtitle: String? = null
    private var appliedTransparentVideo: String? = null
    private var transparentVideoRequestedAtNanos = 0L
    private var transparentVideoConfirmed = false
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

    @Volatile private var visibleCropInternal: SubtitleCrop? = null
    @Volatile private var lastVisibleCropInternal: SubtitleCrop? = null
    private var lastRenderNanos = 0L

    private var scanFbo = 0
    private var scanTex = 0
    private var scanWidth = 0
    private var scanHeight = 0
    private var scanPixels: java.nio.ByteBuffer? = null

    val visibleCrop: SubtitleCrop?
        get() = visibleCropInternal

    /** Last non-empty cue, useful as a small editor handle between subtitle events. */
    val editorCrop: SubtitleCrop?
        get() = visibleCropInternal ?: lastVisibleCropInternal

    /**
     * Internal subtitle canvas. It keeps the source aspect ratio but is capped at
     * 960x540. The final HUD only composites the tight alpha crop, so this is
     * plenty for PGS while avoiding another full-screen render pass.
     */
    val renderCanvasWidth: Int
        get() = renderCanvasSize().first

    val renderCanvasHeight: Int
        get() = renderCanvasSize().second

    val failureReason: String?
        get() = sourceFailure ?: permanentFailure

    val canRenderCurrentSource: Boolean
        get() = permanentFailure == null && sourceFailure == null

    val active: Boolean
        get() = desiredEnabled && desiredSource != null && desiredSid != null && canRenderCurrentSource

    val readyForDisplay: Boolean
        get() = active && fileLoaded && requestedSource == desiredSource &&
            appliedTransparentVideo != null && transparentVideoConfirmed &&
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
    fun configure(
        source: String?,
        sid: Int?,
        enabled: Boolean,
        externalSubtitle: String? = null,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
    ) {
        val external = externalSubtitle?.takeIf { it.isNotBlank() }
        val externalSupported = external == null || supportsSource(external)
        val dimensionsValid = videoWidth > 0 && videoHeight > 0
        val supported = enabled && sid != null && supportsSource(source) && externalSupported && dimensionsValid
        val sizeChanged = desiredVideoWidth != videoWidth || desiredVideoHeight != videoHeight
        desiredEnabled = supported
        desiredSource = if (supported) source else null
        desiredSid = if (supported) sid else null
        desiredExternalSubtitle = if (supported) external else null
        desiredVideoWidth = if (supported) videoWidth else 0
        desiredVideoHeight = if (supported) videoHeight else 0

        if (!supported) {
            // Missing dimensions immediately after FILE_LOADED are transient, not a
            // source failure. MpvPlayer marks presentation dirty again as soon as
            // dwidth/dheight arrive.
            sourceFailure = null
            frameReady.set(false)
            return
        }

        if (requestedSource != source) {
            sourceFailure = null
            fileLoaded = false
            appliedSid = null
            appliedExternalSubtitle = null
            appliedTransparentVideo = null
            transparentVideoRequestedAtNanos = 0L
            transparentVideoConfirmed = false
            alphaValidated = false
            visibleCropInternal = null
            lastVisibleCropInternal = null
            needsExactSync = true
        } else if (sizeChanged) {
            // The media is already loaded; only replace the generated alpha canvas.
            // Do not clear fileLoaded or we would wait for a FILE_LOADED event that
            // will never arrive for a mere display-size update.
            appliedTransparentVideo = null
            transparentVideoRequestedAtNanos = 0L
            transparentVideoConfirmed = false
            alphaValidated = false
            frameReady.set(true)
        }
        frameReady.set(true)
    }

    /** Allow an explicit UI retry after a recoverable per-source failure. */
    fun retryCurrentSource() {
        // Do not disturb an already healthy helper just because a config setter
        // used force=true. Re-arm only a recoverable per-source failure; otherwise
        // we would keep adding duplicate external transparent video tracks.
        if (permanentFailure != null || sourceFailure == null) return
        sourceFailure = null
        alphaValidated = false
        visibleCropInternal = null
        appliedTransparentVideo = null
        transparentVideoRequestedAtNanos = 0L
        transparentVideoConfirmed = false
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
            appliedTransparentVideo = null
            transparentVideoRequestedAtNanos = 0L
            transparentVideoConfirmed = false
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

        // V5.7: select a real transparent RGBA video frame. `background=none`
        // cannot create alpha out of thin air when mpv has no video track; on
        // affected builds the old vid=no-only path therefore produced an opaque
        // canvas and failed validation. A transparent PNG keeps the original
        // media's audio/subtitle clock while giving libmpv an alpha-bearing frame.
        // The dummy frame only exists to give libmpv a transparent RGBA video
        // surface. It does not need to be source-resolution; matching our capped
        // render canvas avoids decoding/scaling a needless 1080p/4K transparent PNG.
        val (dummyW, dummyH) = renderCanvasSize()
        val transparentVideo = ensureTransparentVideo(dummyW, dummyH) ?: return false
        if (appliedTransparentVideo != transparentVideo) {
            command("video-add", transparentVideo, "select", "MpvCraft transparent subtitle canvas")
            appliedTransparentVideo = transparentVideo
            transparentVideoRequestedAtNanos = System.nanoTime()
            transparentVideoConfirmed = false
            alphaValidated = false
            needsExactSync = true
            frameReady.set(true)
            return false
        }
        if (!transparentVideoConfirmed) {
            transparentVideoConfirmed = transparentVideoIsSelected()
            if (!transparentVideoConfirmed) {
                if (transparentVideoRequestedAtNanos != 0L &&
                    System.nanoTime() - transparentVideoRequestedAtNanos > LOAD_TIMEOUT_NS
                ) {
                    sourceFailure = "Detached bitmap subtitles could not select the transparent RGBA canvas"
                    MpvCraft.logger.warn(sourceFailure)
                }
                return false
            }
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

        /*
         * Keep the pre-initialize profile deliberately tiny.
         *
         * V5.7 configured a large collection of cosmetic/runtime options before
         * mpv_initialize(). That works on recent mpv, but some Windows libmpv
         * builds defer validation of one or more of those values until core init;
         * the only error returned to us is then MPV_ERROR_INVALID_PARAMETER.
         *
         * The primary MpvPlayer already proves that the options below are accepted
         * by the user's libmpv. Everything specific to the detached helper is
         * therefore moved to runtime properties after mpv_initialize(). Optional
         * compatibility settings are probed individually and never poison startup.
         */
        fun requiredOption(name: String, value: String): Boolean {
            val rc = library.mpv_set_option_string(h, name, value)
            if (rc >= 0) return true
            permanentFailure =
                "Detached bitmap subtitle bootstrap option $name=$value failed: ${library.mpv_error_string(rc)}"
            MpvCraft.logger.warn(permanentFailure)
            return false
        }

        val bootstrapOk =
            requiredOption("vo", "libmpv") &&
            requiredOption("hwdec", "no") &&
            requiredOption("terminal", "no") &&
            requiredOption("idle", "yes") &&
            requiredOption("keep-open", "yes") &&
            requiredOption("osc", "no") &&
            requiredOption("input-default-bindings", "no") &&
            requiredOption("video-timing-offset", "0") &&
            // Match the known-working primary core at startup. We enable subtitle
            // visibility after initialization, before any media is loaded.
            requiredOption("sub-visibility", "no")

        if (!bootstrapOk) {
            library.mpv_terminate_destroy(h)
            lib = null
            return false
        }

        val rc = library.mpv_initialize(h)
        if (rc < 0) {
            permanentFailure =
                "Detached bitmap subtitle minimal mpv_initialize failed: ${library.mpv_error_string(rc)}"
            library.mpv_terminate_destroy(h)
            lib = null
            MpvCraft.logger.warn(permanentFailure)
            return false
        }
        handle = h

        // Detached-helper-specific settings are runtime properties. Unsupported
        // optional knobs are logged and skipped instead of making the entire second
        // core fail at initialization.
        fun runtimeProperty(name: String, value: String, required: Boolean = false): Boolean {
            val propertyRc = library.mpv_set_property_string(h, name, value)
            if (propertyRc >= 0) return true
            val message =
                "Detached bitmap subtitle property $name=$value rejected: ${library.mpv_error_string(propertyRc)}"
            if (required) {
                permanentFailure = message
                MpvCraft.logger.warn(message)
            } else {
                MpvCraft.logger.debug(message)
            }
            return false
        }

        // Required behavioural state. These are ordinary mpv properties and are
        // available on the same core/version as the primary player.
        val runtimeOk =
            runtimeProperty("pause", "yes", required = true) &&
            runtimeProperty("mute", "yes", required = true) &&
            runtimeProperty("sub-visibility", "yes", required = true) &&
            runtimeProperty("vid", "no", required = true)

        if (!runtimeOk) {
            handle = null
            library.mpv_terminate_destroy(h)
            lib = null
            return false
        }

        // Prefer mpv's realtime null AO because it provides a stable playback clock
        // without opening a second audible device. If a custom/old build omitted the
        // null AO, muted default audio is still safe and the helper continues.
        runtimeProperty("ao", "null")
        runtimeProperty("volume", "0")

        // Static external PNG canvas. `inf` is supported by current mpv, but older
        // builds can simply keep their default image duration; clock sync + exact
        // seeks still keep the subtitle helper usable.
        runtimeProperty("image-display-duration", "inf")
        runtimeProperty("audio-display", "no")
        runtimeProperty("osd-level", "0")
        runtimeProperty("osd-bar", "no")

        // Preserve alpha where supported. mpv >= 0.38 accepts background=none.
        // Older releases used a color-valued background option. Probe both schemes
        // after core initialization so an unsupported variant cannot break startup.
        val modernBackground = runtimeProperty("background", "none")
        if (modernBackground) {
            runtimeProperty("background-color", "#00000000")
        } else {
            runtimeProperty("alpha", "yes")
            runtimeProperty("background", "#00000000")
        }
        runtimeProperty("force-rgba-osd-rendering", "yes")
        runtimeProperty("blend-subtitles", "no")

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
        MpvCraft.logger.info("Detached bitmap subtitle renderer initialized (compatibility bootstrap)")
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
                        appliedTransparentVideo = null
                        transparentVideoRequestedAtNanos = 0L
                        transparentVideoConfirmed = false
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

    /**
     * Consume libmpv's update callback and return true only when mpv actually has
     * a new renderable frame. Older code treated every callback as a redraw, which
     * caused the subtitle helper to repaint continuously even when the PGS bitmap
     * had not changed.
     */
    fun consumePendingRedraw(): Boolean {
        val ctx = renderCtx ?: return false
        val library = lib ?: return false
        if (!hasOwningGlContext()) return false
        if (!frameReady.getAndSet(false)) return false
        val flags = library.mpv_render_context_update(ctx)
        return (flags and Mpv.RENDER_UPDATE_FRAME) != 0L
    }

    fun mayRenderNow(force: Boolean = false): Boolean {
        if (force) return true
        return System.nanoTime() - lastRenderNanos >= MIN_RENDER_INTERVAL_NS
    }

    private fun renderCanvasSize(): Pair<Int, Int> {
        val sw = desiredVideoWidth.coerceAtLeast(16)
        val sh = desiredVideoHeight.coerceAtLeast(16)
        val scale = minOf(1.0, MAX_RENDER_WIDTH.toDouble() / sw, MAX_RENDER_HEIGHT.toDouble() / sh)
        return (sw * scale).toInt().coerceAtLeast(16) to (sh * scale).toInt().coerceAtLeast(16)
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
        // The PiP renderer calls prepareForRender() before deciding whether a
        // throttled frame is due. Do not repeat property/sync work here.
        if (!active || !fileLoaded) return false
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
        updateVisibleCrop()
        lastRenderNanos = System.nanoTime()
        return true
    }

    /**
     * Downsample the alpha plane to a tiny probe FBO and find a tight bounding box.
     * This avoids both the giant full-screen /mpv hud handle and a full-resolution
     * glReadPixels stall. The scan is only performed when the throttled helper
     * actually renders a new frame.
     */
    private fun updateVisibleCrop() {
        if (targetFbo == 0 || targetWidth <= 0 || targetHeight <= 0) {
            visibleCropInternal = null
            return
        }
        if (!ensureScanTarget()) {
            // Cropping is an optimisation/UI feature, not a reason to lose PGS.
            visibleCropInternal = SubtitleCrop(0, 0, targetWidth, targetHeight)
            return
        }

        GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
        GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, targetFbo)
        GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, scanFbo)
        GL33C.glBlitFramebuffer(
            0, 0, targetWidth, targetHeight,
            0, 0, scanWidth, scanHeight,
            GL33C.GL_COLOR_BUFFER_BIT,
            GL33C.GL_LINEAR,
        )

        GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, scanFbo)
        val needed = scanWidth * scanHeight * 4
        val existing = scanPixels
        val pixels = if (existing == null || existing.capacity() < needed) {
            BufferUtils.createByteBuffer(needed).also { scanPixels = it }
        } else {
            existing
        }
        pixels.clear()
        pixels.limit(needed)
        GL33C.glReadPixels(0, 0, scanWidth, scanHeight, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels)

        var minX = scanWidth
        var minY = scanHeight
        var maxX = -1
        var maxY = -1
        for (y in 0 until scanHeight) {
            val row = y * scanWidth * 4
            for (x in 0 until scanWidth) {
                val alpha = pixels.get(row + x * 4 + 3).toInt() and 0xFF
                if (alpha > ALPHA_THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            visibleCropInternal = null
            return
        }

        // A little breathing room prevents anti-aliased edges from being clipped.
        minX = (minX - 2).coerceAtLeast(0)
        minY = (minY - 2).coerceAtLeast(0)
        maxX = (maxX + 2).coerceAtMost(scanWidth - 1)
        maxY = (maxY + 2).coerceAtMost(scanHeight - 1)

        val x0 = (minX.toDouble() / scanWidth * targetWidth).toInt().coerceIn(0, targetWidth - 1)
        val x1 = kotlin.math.ceil((maxX + 1).toDouble() / scanWidth * targetWidth).toInt().coerceIn(x0 + 1, targetWidth)
        // Readback Y is OpenGL bottom-up; expose crop coordinates top-down to HUD code.
        val glY0 = (minY.toDouble() / scanHeight * targetHeight).toInt().coerceIn(0, targetHeight - 1)
        val glY1 = kotlin.math.ceil((maxY + 1).toDouble() / scanHeight * targetHeight).toInt().coerceIn(glY0 + 1, targetHeight)
        val topY = targetHeight - glY1
        val crop = SubtitleCrop(x0, topY, x1 - x0, glY1 - glY0)
        visibleCropInternal = crop
        lastVisibleCropInternal = crop
    }

    private fun ensureScanTarget(): Boolean {
        val scale = minOf(1.0, MAX_SCAN_WIDTH.toDouble() / targetWidth, MAX_SCAN_HEIGHT.toDouble() / targetHeight)
        val w = (targetWidth * scale).toInt().coerceAtLeast(8)
        val h = (targetHeight * scale).toInt().coerceAtLeast(8)
        if (scanFbo != 0 && scanWidth == w && scanHeight == h) return true

        if (scanFbo != 0) GL33C.glDeleteFramebuffers(scanFbo)
        if (scanTex != 0) GL33C.glDeleteTextures(scanTex)
        scanFbo = 0
        scanTex = 0

        val tex = GL33C.glGenTextures()
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, tex)
        GL33C.glTexImage2D(
            GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, w, h, 0,
            GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?,
        )
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR)

        val fbo = GL33C.glGenFramebuffers()
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo)
        GL33C.glFramebufferTexture2D(
            GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, tex, 0,
        )
        if (GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER) != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            GL33C.glDeleteFramebuffers(fbo)
            GL33C.glDeleteTextures(tex)
            return false
        }
        scanFbo = fbo
        scanTex = tex
        scanWidth = w
        scanHeight = h
        scanPixels = null
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
                "libmpv still returned an opaque subtitle canvas even with the RGBA dummy video; using attached image subtitles"
            MpvCraft.logger.warn(sourceFailure)
            return false
        }
        return true
    }

    /**
     * Generate/cache the transparent video used by the helper. V5.7.2 passes the
     * capped subtitle-canvas size here instead of the original 1080p/4K dimensions.
     * PNG compresses the all-zero ARGB image to a tiny file.
     */
    private fun ensureTransparentVideo(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val safeW = width.coerceIn(16, 8192)
        val safeH = height.coerceIn(16, 8192)
        return runCatching {
            val dir = File(MpvCraft.mc.gameDirectory, "cache/mpvcraft")
            if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")
            val file = File(dir, "transparent-${safeW}x${safeH}.png")
            if (!file.isFile || file.length() == 0L) {
                val image = BufferedImage(safeW, safeH, BufferedImage.TYPE_INT_ARGB)
                try {
                    if (!ImageIO.write(image, "png", file)) error("No PNG writer available")
                } finally {
                    image.flush()
                }
            }
            file.absolutePath
        }.onFailure { t ->
            sourceFailure = "Could not create transparent subtitle canvas: ${t.message ?: t.javaClass.simpleName}"
            MpvCraft.logger.warn(sourceFailure, t)
        }.getOrNull()
    }

    private fun transparentVideoIsSelected(): Boolean {
        if (appliedTransparentVideo == null) return false
        // current-tracks/... redirects to the selected track. `external=yes` is
        // stable across mpv versions and avoids path-normalisation differences.
        return getProperty("current-tracks/video/external") == "yes"
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
        if (scanFbo != 0) GL33C.glDeleteFramebuffers(scanFbo)
        if (scanTex != 0) GL33C.glDeleteTextures(scanTex)
        targetFbo = 0
        targetTex = 0
        targetWidth = 0
        targetHeight = 0
        scanFbo = 0
        scanTex = 0
        scanWidth = 0
        scanHeight = 0
        scanPixels = null
        visibleCropInternal = null
    }

    fun deactivate(clearFailure: Boolean = true) {
        desiredEnabled = false
        desiredSource = null
        desiredSid = null
        desiredExternalSubtitle = null
        desiredVideoWidth = 0
        desiredVideoHeight = 0
        if (clearFailure) sourceFailure = null
        visibleCropInternal = null
        lastVisibleCropInternal = null
        frameReady.set(false)
        if (initialized && handle != null) command("stop")
        requestedSource = null
        fileLoaded = false
        appliedSid = null
        appliedExternalSubtitle = null
        appliedTransparentVideo = null
        transparentVideoRequestedAtNanos = 0L
        transparentVideoConfirmed = false
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
        visibleCropInternal = null
        lastVisibleCropInternal = null
        lastRenderNanos = 0L
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
