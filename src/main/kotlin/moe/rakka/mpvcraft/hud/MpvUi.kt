package moe.rakka.mpvcraft.hud

import moe.rakka.mpvcraft.MpvCraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * MpvCraft UI text renderer.
 *
 * Minecraft's bitmap/unifont faces are intentionally not used for MpvCraft's
 * own visual text.  Instead we rasterize a clean *system* sans-serif face at
 * runtime through Java's logical SansSerif composite face into small dynamic
 * textures. This keeps accents and broad Unicode fallback without redistributing a
 * font file with the mod.
 *
 * Components returned by [text] are still used for narration/chat/widget
 * plumbing; all visible MpvCraft UI labels are drawn through [draw].
 */
object MpvUi {
    const val UI_SIZE = 12
    const val TITLE_SIZE = 14
    const val SECTION_SIZE = 10
    const val SUBTITLE_SIZE = 13

    private const val TEXTURE_CACHE_LIMIT = 128
    private const val TEXTURE_PAD = 2

    private val frc = FontRenderContext(null, true, true)
    private val sequence = AtomicInteger()

    private data class Key(
        val text: String,
        val size: Int,
        val color: Int,
        val bold: Boolean,
        val rasterScale: Int,
    )

    private data class CachedText(
        val textureId: Identifier,
        val textureWidth: Int,
        val textureHeight: Int,
        val drawWidth: Int,
        val drawHeight: Int,
        val drawPad: Int,
    )

    private val cache = object : LinkedHashMap<Key, CachedText>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CachedText>?): Boolean {
            if (size <= TEXTURE_CACHE_LIMIT || eldest == null) return false
            runCatching { MpvCraft.mc.getTextureManager().release(eldest.value.textureId) }
            return true
        }
    }

    /**
     * Java's logical SansSerif is deliberately used instead of a single physical
     * font family. On Windows it resolves to a normal UI sans face while Java can
     * transparently fall back to other installed fonts for glyphs the primary
     * face does not contain (CJK, Cyrillic, Arabic, etc.).
     */
    private val baseFont: Font by lazy {
        Font(Font.SANS_SERIF, Font.PLAIN, UI_SIZE)
    }

    fun text(value: String): MutableComponent = Component.literal(value)

    fun width(value: String, size: Int = UI_SIZE, bold: Boolean = false): Int {
        if (value.isEmpty()) return 0
        val bounds = font(size, bold).getStringBounds(value, frc)
        return ceil(bounds.width).toInt().coerceAtLeast(0)
    }

    fun lineHeight(size: Int = UI_SIZE, bold: Boolean = false): Int {
        val metrics = font(size, bold).getLineMetrics("Ag", frc)
        return ceil(metrics.height.toDouble()).toInt().coerceAtLeast(1)
    }

    fun clip(value: String, maxWidth: Int, size: Int = UI_SIZE, bold: Boolean = false): String {
        if (maxWidth <= 0) return ""
        if (width(value, size, bold) <= maxWidth) return value

        val ellipsis = "..."
        if (width(ellipsis, size, bold) > maxWidth) return ""

        var end = value.length
        while (end > 0) {
            val cp = value.codePointBefore(end)
            end -= Character.charCount(cp)
            val candidate = value.substring(0, end) + ellipsis
            if (width(candidate, size, bold) <= maxWidth) return candidate
        }
        return ellipsis
    }

    fun draw(
        graphics: GuiGraphicsExtractor,
        value: String,
        x: Int,
        y: Int,
        color: Int,
        size: Int = UI_SIZE,
        bold: Boolean = false,
        physicalPixels: Boolean = false,
        /** Extra raster density for text that will be enlarged by a pose transform. */
        qualityScale: Float = 1f,
    ) {
        if (value.isEmpty()) return
        val requestedDensity = if (physicalPixels) {
            qualityScale
        } else {
            uiRasterScale().toFloat() * qualityScale
        }
        val rasterScale = rasterScaleFor(requestedDensity)
        val entry = textureFor(Key(value, size.coerceAtLeast(6), color, bold, rasterScale)) ?: run {
            // Defensive fallback for unusual Java runtimes lacking java.desktop.
            graphics.text(MpvCraft.mc.font, Component.literal(value), x, y, color, false)
            return
        }
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            entry.textureId,
            x - entry.drawPad,
            y - entry.drawPad,
            0f,
            0f,
            entry.drawWidth,
            entry.drawHeight,
            entry.textureWidth,
            entry.textureHeight,
            entry.textureWidth,
            entry.textureHeight,
        )
    }

    fun drawCentered(
        graphics: GuiGraphicsExtractor,
        value: String,
        centerX: Int,
        y: Int,
        color: Int,
        size: Int = UI_SIZE,
        bold: Boolean = false,
        physicalPixels: Boolean = false,
        qualityScale: Float = 1f,
    ) {
        draw(
            graphics,
            value,
            centerX - width(value, size, bold) / 2,
            y,
            color,
            size,
            bold,
            physicalPixels,
            qualityScale,
        )
    }

    /** Releases all dynamic glyph-line textures (also called on client shutdown). */
    @Synchronized
    fun clearCache() {
        cache.values.forEach { runCatching { MpvCraft.mc.getTextureManager().release(it.textureId) } }
        cache.clear()
    }

    @Synchronized
    private fun textureFor(key: Key): CachedText? {
        cache[key]?.let { return it }

        return try {
            val scale = key.rasterScale.coerceIn(1, 8)
            val awtFont = font(key.size * scale, key.bold)
            val lm = awtFont.getLineMetrics(key.text.ifEmpty { "Ag" }, frc)
            val textW = ceil(awtFont.getStringBounds(key.text, frc).width).toInt().coerceAtLeast(1)
            val textH = ceil(lm.height.toDouble()).toInt().coerceAtLeast(1)
            val rasterPad = TEXTURE_PAD * scale
            val width = textW + rasterPad * 2
            val height = textH + rasterPad * 2

            val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = buffered.createGraphics()
            try {
                configureGraphics(g)
                g.font = awtFont
                g.color = Color(key.color, true)
                val baseline = rasterPad + ceil(lm.ascent.toDouble()).toInt()
                g.drawString(key.text, rasterPad, baseline)
            } finally {
                g.dispose()
            }

            val texture = DynamicTexture("MpvCraft system text", width, height, true)
            val pixels = texture.getPixels()
            for (py in 0 until height) {
                for (px in 0 until width) {
                    pixels.setPixel(px, py, buffered.getRGB(px, py))
                }
            }

            val id = Identifier.fromNamespaceAndPath(
                MpvCraft.MOD_ID,
                "dynamic/system_text_${sequence.incrementAndGet()}",
            )
            MpvCraft.mc.getTextureManager().register(id, texture)
            texture.upload()

            CachedText(
                textureId = id,
                textureWidth = width,
                textureHeight = height,
                drawWidth = ceil(width / scale.toDouble()).toInt(),
                drawHeight = ceil(height / scale.toDouble()).toInt(),
                drawPad = TEXTURE_PAD,
            ).also { cache[key] = it }
        } catch (t: Throwable) {
            MpvCraft.logger.error("Failed to rasterize MpvCraft UI text", t)
            null
        }
    }

    private fun uiRasterScale(): Int =
        ceil(MpvCraft.mc.window.guiScale.toDouble()).toInt().coerceIn(1, 8)

    private fun rasterScaleFor(requested: Float): Int =
        ceil(requested.coerceAtLeast(1f).toDouble()).toInt().coerceIn(1, 8)

    private fun font(size: Int, bold: Boolean): Font =
        baseFont.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size.toFloat())

    private fun configureGraphics(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }
}
