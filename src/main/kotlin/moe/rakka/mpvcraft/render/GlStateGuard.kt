package moe.rakka.mpvcraft.render

import org.lwjgl.opengl.GL33C
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Isolates a foreign OpenGL renderer from Minecraft's GL state.
 *
 * libmpv's OpenGL render API has a stricter contract than a normal Minecraft
 * draw call: it expects the incoming GL state to be close to the OpenGL
 * defaults, and it only promises to leave defaults behind. Minecraft, on the
 * other hand, deliberately keeps a large amount of non-default state cached.
 *
 * Merely saving/restoring Minecraft's state is therefore not enough. The guest
 * must also start from a clean baseline. [guarded] does both:
 *
 *   Minecraft state -> save -> OpenGL defaults -> guest -> restore -> Minecraft
 *
 * All calls in this object must happen with Minecraft's OpenGL context current.
 */
object GlStateGuard {

    // libmpv allocates texture/UBO bindings from low indexes. Tracking the first
    // 16 covers the OpenGL 3.3 minimum and is deliberately larger than MC's GUI
    // use in practice without turning every video frame into hundreds of state
    // queries. If a future renderer starts using higher slots, raise this value.
    private const val TRACKED_BINDINGS = 16

    private val viewport = IntArray(4)
    private val scissorBox = IntArray(4)
    private val colorMask = BooleanArray(4)
    private val clearColor = FloatArray(4)

    private val texture1dBindings = IntArray(TRACKED_BINDINGS)
    private val texture2dBindings = IntArray(TRACKED_BINDINGS)
    private val texture3dBindings = IntArray(TRACKED_BINDINGS)
    private val textureRectBindings = IntArray(TRACKED_BINDINGS)
    private val samplerBindings = IntArray(TRACKED_BINDINGS)
    private val uniformBufferBindings = IntArray(TRACKED_BINDINGS)
    private val uniformBufferStarts = LongArray(TRACKED_BINDINGS)
    private val uniformBufferSizes = LongArray(TRACKED_BINDINGS)

    private val maskBuf: ByteBuffer = ByteBuffer.allocateDirect(4)
    private val clearColorBuf: FloatBuffer = ByteBuffer.allocateDirect(4 * Float.SIZE_BYTES)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var program = 0
    private var vao = 0
    private var arrayBuffer = 0
    private var elementBuffer = 0
    private var pixelPackBuffer = 0
    private var pixelUnpackBuffer = 0
    private var uniformBuffer = 0
    private var copyReadBuffer = 0
    private var copyWriteBuffer = 0
    private var drawFbo = 0
    private var readFbo = 0
    private var renderbuffer = 0
    private var activeTexture = 0

    private var blendEnabled = false
    private var blendSrcRgb = 0
    private var blendDstRgb = 0
    private var blendSrcAlpha = 0
    private var blendDstAlpha = 0
    private var blendEqRgb = 0
    private var blendEqAlpha = 0

    private var depthTest = false
    private var depthMask = false
    private var depthFunc = 0
    private var cullFace = false
    private var cullFaceMode = 0
    private var frontFace = 0
    private var scissorTest = false
    private var stencilTest = false
    private var dither = false
    private var framebufferSrgb = false
    private var rasterizerDiscard = false
    private var primitiveRestart = false
    private var primitiveRestartIndex = 0
    private var polygonOffsetFill = false
    private var polygonOffsetLine = false
    private var polygonOffsetPoint = false
    private var polygonOffsetFactor = 0f
    private var polygonOffsetUnits = 0f

    private var packAlignment = 4
    private var packRowLength = 0
    private var packSkipRows = 0
    private var packSkipPixels = 0
    private var unpackAlignment = 4
    private var unpackRowLength = 0
    private var unpackSkipRows = 0
    private var unpackSkipPixels = 0
    private var unpackImageHeight = 0
    private var unpackSkipImages = 0

    private var textureBindingCount = TRACKED_BINDINGS
    private var uboBindingCount = TRACKED_BINDINGS

    /**
     * Runs [body] with Minecraft state isolated from the guest renderer.
     *
     * The state is restored even when the guest throws. This is intentionally
     * not inline: keeping save/reset/restore private makes it much harder for a
     * caller to accidentally use only half of the contract.
     */
    fun <T> guarded(body: () -> T): T {
        save()
        resetForGuest()
        try {
            return body()
        } finally {
            restore()
        }
    }

    private fun save() {
        textureBindingCount = minOf(
            TRACKED_BINDINGS,
            GL33C.glGetInteger(GL33C.GL_MAX_TEXTURE_IMAGE_UNITS).coerceAtLeast(1),
        )
        uboBindingCount = minOf(
            TRACKED_BINDINGS,
            GL33C.glGetInteger(GL33C.GL_MAX_UNIFORM_BUFFER_BINDINGS).coerceAtLeast(1),
        )

        program = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM)
        vao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING)
        arrayBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING)
        elementBuffer = GL33C.glGetInteger(GL33C.GL_ELEMENT_ARRAY_BUFFER_BINDING)
        pixelPackBuffer = GL33C.glGetInteger(GL33C.GL_PIXEL_PACK_BUFFER_BINDING)
        pixelUnpackBuffer = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING)
        uniformBuffer = GL33C.glGetInteger(GL33C.GL_UNIFORM_BUFFER_BINDING)
        copyReadBuffer = GL33C.glGetInteger(GL33C.GL_COPY_READ_BUFFER)
        copyWriteBuffer = GL33C.glGetInteger(GL33C.GL_COPY_WRITE_BUFFER)
        drawFbo = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING)
        readFbo = GL33C.glGetInteger(GL33C.GL_READ_FRAMEBUFFER_BINDING)
        renderbuffer = GL33C.glGetInteger(GL33C.GL_RENDERBUFFER_BINDING)
        activeTexture = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE)

        for (i in 0 until textureBindingCount) {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + i)
            texture1dBindings[i] = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_1D)
            texture2dBindings[i] = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D)
            texture3dBindings[i] = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_3D)
            textureRectBindings[i] = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_RECTANGLE)
            samplerBindings[i] = GL33C.glGetInteger(GL33C.GL_SAMPLER_BINDING)
        }
        GL33C.glActiveTexture(activeTexture)

        for (i in 0 until uboBindingCount) {
            uniformBufferBindings[i] = GL33C.glGetIntegeri(GL33C.GL_UNIFORM_BUFFER_BINDING, i)
            uniformBufferStarts[i] = GL33C.glGetInteger64i(GL33C.GL_UNIFORM_BUFFER_START, i)
            uniformBufferSizes[i] = GL33C.glGetInteger64i(GL33C.GL_UNIFORM_BUFFER_SIZE, i)
        }

        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, viewport)
        GL33C.glGetIntegerv(GL33C.GL_SCISSOR_BOX, scissorBox)

        blendEnabled = GL33C.glIsEnabled(GL33C.GL_BLEND)
        blendSrcRgb = GL33C.glGetInteger(GL33C.GL_BLEND_SRC_RGB)
        blendDstRgb = GL33C.glGetInteger(GL33C.GL_BLEND_DST_RGB)
        blendSrcAlpha = GL33C.glGetInteger(GL33C.GL_BLEND_SRC_ALPHA)
        blendDstAlpha = GL33C.glGetInteger(GL33C.GL_BLEND_DST_ALPHA)
        blendEqRgb = GL33C.glGetInteger(GL33C.GL_BLEND_EQUATION_RGB)
        blendEqAlpha = GL33C.glGetInteger(GL33C.GL_BLEND_EQUATION_ALPHA)

        depthTest = GL33C.glIsEnabled(GL33C.GL_DEPTH_TEST)
        depthMask = GL33C.glGetBoolean(GL33C.GL_DEPTH_WRITEMASK)
        depthFunc = GL33C.glGetInteger(GL33C.GL_DEPTH_FUNC)
        cullFace = GL33C.glIsEnabled(GL33C.GL_CULL_FACE)
        cullFaceMode = GL33C.glGetInteger(GL33C.GL_CULL_FACE_MODE)
        frontFace = GL33C.glGetInteger(GL33C.GL_FRONT_FACE)
        scissorTest = GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST)
        stencilTest = GL33C.glIsEnabled(GL33C.GL_STENCIL_TEST)
        dither = GL33C.glIsEnabled(GL33C.GL_DITHER)
        framebufferSrgb = GL33C.glIsEnabled(GL33C.GL_FRAMEBUFFER_SRGB)
        rasterizerDiscard = GL33C.glIsEnabled(GL33C.GL_RASTERIZER_DISCARD)
        primitiveRestart = GL33C.glIsEnabled(GL33C.GL_PRIMITIVE_RESTART)
        primitiveRestartIndex = GL33C.glGetInteger(GL33C.GL_PRIMITIVE_RESTART_INDEX)
        polygonOffsetFill = GL33C.glIsEnabled(GL33C.GL_POLYGON_OFFSET_FILL)
        polygonOffsetLine = GL33C.glIsEnabled(GL33C.GL_POLYGON_OFFSET_LINE)
        polygonOffsetPoint = GL33C.glIsEnabled(GL33C.GL_POLYGON_OFFSET_POINT)
        polygonOffsetFactor = GL33C.glGetFloat(GL33C.GL_POLYGON_OFFSET_FACTOR)
        polygonOffsetUnits = GL33C.glGetFloat(GL33C.GL_POLYGON_OFFSET_UNITS)

        maskBuf.clear()
        GL33C.glGetBooleanv(GL33C.GL_COLOR_WRITEMASK, maskBuf)
        for (i in 0 until 4) colorMask[i] = maskBuf.get(i).toInt() != 0

        clearColorBuf.clear()
        GL33C.glGetFloatv(GL33C.GL_COLOR_CLEAR_VALUE, clearColorBuf)
        for (i in 0 until 4) clearColor[i] = clearColorBuf.get(i)

        packAlignment = GL33C.glGetInteger(GL33C.GL_PACK_ALIGNMENT)
        packRowLength = GL33C.glGetInteger(GL33C.GL_PACK_ROW_LENGTH)
        packSkipRows = GL33C.glGetInteger(GL33C.GL_PACK_SKIP_ROWS)
        packSkipPixels = GL33C.glGetInteger(GL33C.GL_PACK_SKIP_PIXELS)
        unpackAlignment = GL33C.glGetInteger(GL33C.GL_UNPACK_ALIGNMENT)
        unpackRowLength = GL33C.glGetInteger(GL33C.GL_UNPACK_ROW_LENGTH)
        unpackSkipRows = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_ROWS)
        unpackSkipPixels = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_PIXELS)
        unpackImageHeight = GL33C.glGetInteger(GL33C.GL_UNPACK_IMAGE_HEIGHT)
        unpackSkipImages = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_IMAGES)
    }

    /** Put the context into the baseline libmpv documents as its input contract. */
    private fun resetForGuest() {
        GL33C.glUseProgram(0)
        GL33C.glBindVertexArray(0)
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0)
        // ELEMENT_ARRAY_BUFFER is VAO state. With VAO 0 selected in a core
        // profile, attempting to mutate it can itself be GL_INVALID_OPERATION.
        GL33C.glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, 0)
        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0)
        GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, 0)
        GL33C.glBindBuffer(GL33C.GL_COPY_READ_BUFFER, 0)
        GL33C.glBindBuffer(GL33C.GL_COPY_WRITE_BUFFER, 0)

        for (i in 0 until uboBindingCount) {
            GL33C.glBindBufferBase(GL33C.GL_UNIFORM_BUFFER, i, 0)
        }
        // glBindBufferBase also updates the generic target binding.
        GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, 0)

        GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, 0)
        GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, 0)
        GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, 0)

        for (i in 0 until textureBindingCount) {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + i)
            GL33C.glBindTexture(GL33C.GL_TEXTURE_1D, 0)
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0)
            GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, 0)
            GL33C.glBindTexture(GL33C.GL_TEXTURE_RECTANGLE, 0)
            GL33C.glBindSampler(i, 0)
        }
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0)

        GL33C.glDisable(GL33C.GL_BLEND)
        GL33C.glBlendFuncSeparate(GL33C.GL_ONE, GL33C.GL_ZERO, GL33C.GL_ONE, GL33C.GL_ZERO)
        GL33C.glBlendEquationSeparate(GL33C.GL_FUNC_ADD, GL33C.GL_FUNC_ADD)

        GL33C.glDisable(GL33C.GL_DEPTH_TEST)
        GL33C.glDepthMask(true)
        GL33C.glDepthFunc(GL33C.GL_LESS)
        GL33C.glDisable(GL33C.GL_CULL_FACE)
        GL33C.glCullFace(GL33C.GL_BACK)
        GL33C.glFrontFace(GL33C.GL_CCW)
        GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
        GL33C.glDisable(GL33C.GL_STENCIL_TEST)
        // libmpv explicitly disables dithering when its GL renderer is created.
        GL33C.glDisable(GL33C.GL_DITHER)
        GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB)
        GL33C.glDisable(GL33C.GL_RASTERIZER_DISCARD)
        GL33C.glDisable(GL33C.GL_PRIMITIVE_RESTART)
        GL33C.glPrimitiveRestartIndex(0)
        GL33C.glDisable(GL33C.GL_POLYGON_OFFSET_FILL)
        GL33C.glDisable(GL33C.GL_POLYGON_OFFSET_LINE)
        GL33C.glDisable(GL33C.GL_POLYGON_OFFSET_POINT)
        GL33C.glPolygonOffset(0f, 0f)
        GL33C.glColorMask(true, true, true, true)
        GL33C.glClearColor(0f, 0f, 0f, 0f)

        GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 4)
        GL33C.glPixelStorei(GL33C.GL_PACK_ROW_LENGTH, 0)
        GL33C.glPixelStorei(GL33C.GL_PACK_SKIP_ROWS, 0)
        GL33C.glPixelStorei(GL33C.GL_PACK_SKIP_PIXELS, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 4)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_IMAGE_HEIGHT, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_IMAGES, 0)
    }

    private fun restore() {
        // Restore global object bindings first. VAO must precede the element
        // buffer because GL_ELEMENT_ARRAY_BUFFER_BINDING is VAO state.
        GL33C.glUseProgram(program)
        GL33C.glBindVertexArray(vao)
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, arrayBuffer)
        GL33C.glBindBuffer(GL33C.GL_ELEMENT_ARRAY_BUFFER, elementBuffer)
        GL33C.glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, pixelPackBuffer)
        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer)
        GL33C.glBindBuffer(GL33C.GL_COPY_READ_BUFFER, copyReadBuffer)
        GL33C.glBindBuffer(GL33C.GL_COPY_WRITE_BUFFER, copyWriteBuffer)

        for (i in 0 until uboBindingCount) {
            val binding = uniformBufferBindings[i]
            if (binding == 0) {
                GL33C.glBindBufferBase(GL33C.GL_UNIFORM_BUFFER, i, 0)
            } else {
                val size = uniformBufferSizes[i]
                if (size > 0L) {
                    GL33C.glBindBufferRange(
                        GL33C.GL_UNIFORM_BUFFER,
                        i,
                        binding,
                        uniformBufferStarts[i],
                        size,
                    )
                } else {
                    GL33C.glBindBufferBase(GL33C.GL_UNIFORM_BUFFER, i, binding)
                }
            }
        }
        // Restore the generic UBO target last because glBindBufferBase mutates it.
        GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, uniformBuffer)

        GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, renderbuffer)

        for (i in 0 until textureBindingCount) {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + i)
            GL33C.glBindTexture(GL33C.GL_TEXTURE_1D, texture1dBindings[i])
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture2dBindings[i])
            GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, texture3dBindings[i])
            GL33C.glBindTexture(GL33C.GL_TEXTURE_RECTANGLE, textureRectBindings[i])
            GL33C.glBindSampler(i, samplerBindings[i])
        }
        GL33C.glActiveTexture(activeTexture)

        GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, drawFbo)
        GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readFbo)

        GL33C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GL33C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])

        setEnabled(GL33C.GL_BLEND, blendEnabled)
        GL33C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
        GL33C.glBlendEquationSeparate(blendEqRgb, blendEqAlpha)

        setEnabled(GL33C.GL_DEPTH_TEST, depthTest)
        GL33C.glDepthMask(depthMask)
        GL33C.glDepthFunc(depthFunc)
        setEnabled(GL33C.GL_CULL_FACE, cullFace)
        GL33C.glCullFace(cullFaceMode)
        GL33C.glFrontFace(frontFace)
        setEnabled(GL33C.GL_SCISSOR_TEST, scissorTest)
        setEnabled(GL33C.GL_STENCIL_TEST, stencilTest)
        setEnabled(GL33C.GL_DITHER, dither)
        setEnabled(GL33C.GL_FRAMEBUFFER_SRGB, framebufferSrgb)
        setEnabled(GL33C.GL_RASTERIZER_DISCARD, rasterizerDiscard)
        setEnabled(GL33C.GL_PRIMITIVE_RESTART, primitiveRestart)
        GL33C.glPrimitiveRestartIndex(primitiveRestartIndex)
        setEnabled(GL33C.GL_POLYGON_OFFSET_FILL, polygonOffsetFill)
        setEnabled(GL33C.GL_POLYGON_OFFSET_LINE, polygonOffsetLine)
        setEnabled(GL33C.GL_POLYGON_OFFSET_POINT, polygonOffsetPoint)
        GL33C.glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits)

        GL33C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
        GL33C.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])

        GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, packAlignment)
        GL33C.glPixelStorei(GL33C.GL_PACK_ROW_LENGTH, packRowLength)
        GL33C.glPixelStorei(GL33C.GL_PACK_SKIP_ROWS, packSkipRows)
        GL33C.glPixelStorei(GL33C.GL_PACK_SKIP_PIXELS, packSkipPixels)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, unpackAlignment)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, unpackRowLength)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, unpackSkipRows)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, unpackSkipPixels)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_IMAGE_HEIGHT, unpackImageHeight)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_IMAGES, unpackSkipImages)
    }

    private fun setEnabled(cap: Int, on: Boolean) {
        if (on) GL33C.glEnable(cap) else GL33C.glDisable(cap)
    }
}
