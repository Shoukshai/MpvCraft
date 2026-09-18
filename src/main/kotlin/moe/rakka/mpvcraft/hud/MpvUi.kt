package moe.rakka.mpvcraft.hud

import moe.rakka.mpvcraft.MpvCraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max

/**
 * MpvCraft UI rasterizer.
 *
 * Minecraft's GUI primitives are pixel-aligned by design. That is ideal for the
 * game, but it made MpvCraft's media controls look like Minecraft widgets even
 * when they were fully custom. MpvCraft therefore rasterizes its own text,
 * rounded surfaces and vector icons at the current GUI density with Java2D and
 * lets Minecraft only composite the resulting RGBA textures.
 *
 * The result is deliberately closer to a normal desktop/media application:
 * anti-aliased corners, round stroke caps, smooth icons and system sans-serif
 * text. Seek icons use separate SVG-derived RGBA resources so their geometry
 * and digit orientation are preserved without requiring a runtime SVG library.
 */
object MpvUi {
    const val UI_SIZE = 12
    const val TITLE_SIZE = 14
    const val SECTION_SIZE = 10
    const val SUBTITLE_SIZE = 13

    enum class Icon {
        PLAY,
        PAUSE,
        REWIND_5,
        FORWARD_5,
        STOP,
        PREVIOUS,
        SKIP,
        FOLDER,
        FILE,
        PLAYLIST,
        VOLUME,
        DISPLAY,
        SUBTITLES,
        AUDIO,
        CHAPTERS,
        INFO,
        LINK,
        HEART,
        MOVE,
        CLOSE,
    }

    private const val TEXTURE_PAD = 2
    private const val TEXT_CACHE_LIMIT = 160
    private const val SURFACE_CACHE_LIMIT = 192
    private const val ICON_CACHE_LIMIT = 128

    private val frc = FontRenderContext(null, true, true)
    private val sequence = AtomicInteger()

    private data class TextKey(
        val text: String,
        val size: Int,
        val color: Int,
        val bold: Boolean,
        val rasterScale: Int,
    )

    private data class SurfaceKey(
        val width: Int,
        val height: Int,
        val radius: Int,
        val fill: Int,
        val border: Int?,
        val borderWidthTenths: Int,
        val rasterScale: Int,
    )

    private data class IconKey(
        val icon: Icon,
        val size: Int,
        val color: Int,
        val rasterScale: Int,
    )

    private data class CachedTexture(
        val textureId: Identifier,
        val textureWidth: Int,
        val textureHeight: Int,
        val drawWidth: Int,
        val drawHeight: Int,
        val drawPad: Int,
    )

    private fun <K> lru(limit: Int) = object : LinkedHashMap<K, CachedTexture>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CachedTexture>?): Boolean {
            if (size <= limit || eldest == null) return false
            release(eldest.value)
            return true
        }
    }

    private val textCache = lru<TextKey>(TEXT_CACHE_LIMIT)
    private val surfaceCache = lru<SurfaceKey>(SURFACE_CACHE_LIMIT)
    private val iconCache = lru<IconKey>(ICON_CACHE_LIMIT)

    /** Java logical SansSerif keeps the UI native-looking and Unicode capable. */
    private val baseFont: Font by lazy { Font(Font.SANS_SERIF, Font.PLAIN, UI_SIZE) }

    fun text(value: String): MutableComponent = Component.literal(value)

    fun width(value: String, size: Int = UI_SIZE, bold: Boolean = false): Int {
        if (value.isEmpty()) return 0
        return ceil(font(size, bold).getStringBounds(value, frc).width).toInt().coerceAtLeast(0)
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
        qualityScale: Float = 1f,
    ) {
        if (value.isEmpty()) return
        val requestedDensity = if (physicalPixels) qualityScale else uiRasterScale().toFloat() * qualityScale
        val rasterScale = rasterScaleFor(requestedDensity)
        val entry = textTexture(TextKey(value, size.coerceAtLeast(6), color, bold, rasterScale)) ?: run {
            graphics.text(MpvCraft.mc.font, Component.literal(value), x, y, color, false)
            return
        }
        blit(graphics, entry, x, y)
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

    /** Smooth, anti-aliased rounded surface with an optional real vector stroke. */
    fun roundedRect(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radius: Int,
        fill: Int,
        border: Int? = null,
        borderWidth: Float = 1f,
        physicalPixels: Boolean = false,
    ) {
        if (width <= 0 || height <= 0) return
        val density = if (physicalPixels) 2 else uiRasterScale()
        val key = SurfaceKey(
            width,
            height,
            radius.coerceIn(0, minOf(width, height) / 2),
            fill,
            border,
            (borderWidth.coerceIn(0.5f, 4f) * 10f).toInt(),
            density,
        )
        val entry = surfaceTexture(key) ?: run {
            graphics.fill(x, y, x + width, y + height, fill)
            return
        }
        blit(graphics, entry, x, y)
    }

    /** Anti-aliased vector icon; no Minecraft-font/pixel-glyph dependency. */
    fun icon(
        graphics: GuiGraphicsExtractor,
        icon: Icon,
        x: Int,
        y: Int,
        size: Int,
        color: Int,
        physicalPixels: Boolean = false,
    ) {
        if (size <= 0) return
        val density = if (physicalPixels) 2 else uiRasterScale()
        val entry = iconTexture(IconKey(icon, size, color, density)) ?: return
        blit(graphics, entry, x, y)
    }

    @Synchronized
    fun clearCache() {
        textCache.values.forEach(::release)
        surfaceCache.values.forEach(::release)
        iconCache.values.forEach(::release)
        textCache.clear()
        surfaceCache.clear()
        iconCache.clear()
    }

    @Synchronized
    private fun textTexture(key: TextKey): CachedTexture? {
        textCache[key]?.let { return it }
        return try {
            val scale = key.rasterScale.coerceIn(1, 8)
            val awtFont = font(key.size * scale, key.bold)
            val lm = awtFont.getLineMetrics(key.text.ifEmpty { "Ag" }, frc)
            val textW = ceil(awtFont.getStringBounds(key.text, frc).width).toInt().coerceAtLeast(1)
            val textH = ceil(lm.height.toDouble()).toInt().coerceAtLeast(1)
            val pad = TEXTURE_PAD * scale
            val buffered = BufferedImage(textW + pad * 2, textH + pad * 2, BufferedImage.TYPE_INT_ARGB)
            buffered.createGraphics().useGraphics { g ->
                configureGraphics(g)
                g.font = awtFont
                g.color = Color(key.color, true)
                g.drawString(key.text, pad, pad + ceil(lm.ascent.toDouble()).toInt())
            }
            registerTexture(buffered, "system_text", scale, TEXTURE_PAD).also { textCache[key] = it }
        } catch (t: Throwable) {
            MpvCraft.logger.error("Failed to rasterize MpvCraft UI text", t)
            null
        }
    }

    @Synchronized
    private fun surfaceTexture(key: SurfaceKey): CachedTexture? {
        surfaceCache[key]?.let { return it }
        return try {
            val scale = key.rasterScale.coerceIn(1, 8)
            val logicalPad = 2
            val pad = logicalPad * scale
            val rw = key.width * scale
            val rh = key.height * scale
            val rr = key.radius * scale.toDouble()
            val image = BufferedImage(rw + pad * 2, rh + pad * 2, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().useGraphics { g ->
                configureGraphics(g)
                val borderWidth = key.borderWidthTenths / 10f * scale
                val inset = if (key.border != null) borderWidth / 2f else 0f
                val shape = RoundRectangle2D.Float(
                    pad + inset,
                    pad + inset,
                    rw - inset * 2,
                    rh - inset * 2,
                    max(0.0, rr * 2 - inset).toFloat(),
                    max(0.0, rr * 2 - inset).toFloat(),
                )
                g.color = Color(key.fill, true)
                g.fill(shape)
                key.border?.let { border ->
                    g.color = Color(border, true)
                    g.stroke = BasicStroke(borderWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(shape)
                }
            }
            registerTexture(image, "surface", scale, logicalPad).also { surfaceCache[key] = it }
        } catch (t: Throwable) {
            MpvCraft.logger.error("Failed to rasterize MpvCraft UI surface", t)
            null
        }
    }

    @Synchronized
    private fun iconTexture(key: IconKey): CachedTexture? {
        iconCache[key]?.let { return it }
        return try {
            val scale = key.rasterScale.coerceIn(1, 8)
            val logicalPad = 2
            val pad = logicalPad * scale
            val s = key.size * scale
            val image = BufferedImage(s + pad * 2, s + pad * 2, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().useGraphics { g ->
                configureGraphics(g)
                g.translate(pad.toDouble(), pad.toDouble())
                g.color = Color(key.color, true)
                drawVectorIcon(g, key.icon, s.toDouble())
            }
            registerTexture(image, "icon", scale, logicalPad).also { iconCache[key] = it }
        } catch (t: Throwable) {
            MpvCraft.logger.error("Failed to rasterize MpvCraft vector icon ${key.icon}", t)
            null
        }
    }

    private fun drawVectorIcon(g: Graphics2D, icon: Icon, size: Double) {
        val u = size / 24.0
        fun stroke(logical: Double = 1.8): BasicStroke = BasicStroke(
            (logical * u).toFloat(),
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
        )
        fun path(vararg points: Pair<Double, Double>, close: Boolean = false): Path2D.Double {
            val p = Path2D.Double()
            if (points.isEmpty()) return p
            p.moveTo(points[0].first * u, points[0].second * u)
            points.drop(1).forEach { p.lineTo(it.first * u, it.second * u) }
            if (close) p.closePath()
            return p
        }
        fun triangle(cx: Double, cy: Double, w: Double, h: Double) {
            g.fill(path(
                (cx - w / 2) to (cy - h / 2),
                (cx + w / 2) to cy,
                (cx - w / 2) to (cy + h / 2),
                close = true,
            ))
        }

        g.stroke = stroke()
        when (icon) {
            Icon.PLAY -> triangle(12.4, 12.0, 11.0, 14.0)
            Icon.PAUSE -> {
                g.fill(RoundRectangle2D.Double(7.1 * u, 5.0 * u, 3.4 * u, 14.0 * u, 1.6 * u, 1.6 * u))
                g.fill(RoundRectangle2D.Double(13.5 * u, 5.0 * u, 3.4 * u, 14.0 * u, 1.6 * u, 1.6 * u))
            }
            Icon.REWIND_5, Icon.FORWARD_5 -> {
                val direction = if (icon == Icon.REWIND_5) MpvSeekIcons.Direction.REWIND else MpvSeekIcons.Direction.FORWARD
                val image = MpvSeekIcons.rasterize(direction, size.toInt(), g.color.rgb)
                g.drawImage(image, 0, 0, null)
            }
            Icon.STOP -> g.fill(RoundRectangle2D.Double(6.2 * u, 6.2 * u, 11.6 * u, 11.6 * u, 2.6 * u, 2.6 * u))
            Icon.PREVIOUS -> {
                triangle(15.5, 12.0, -7.0, 10.0)
                triangle(9.7, 12.0, -7.0, 10.0)
                g.stroke = stroke(2.0)
                g.drawLine((5.0 * u).toInt(), (7.0 * u).toInt(), (5.0 * u).toInt(), (17.0 * u).toInt())
            }
            Icon.SKIP -> {
                triangle(8.5, 12.0, 7.0, 10.0)
                triangle(14.3, 12.0, 7.0, 10.0)
                g.stroke = stroke(2.0)
                g.drawLine((19.0 * u).toInt(), (7.0 * u).toInt(), (19.0 * u).toInt(), (17.0 * u).toInt())
            }
            Icon.FOLDER -> {
                val p = Path2D.Double()
                p.moveTo(3.5 * u, 7.2 * u)
                p.quadTo(3.5 * u, 5.5 * u, 5.2 * u, 5.5 * u)
                p.lineTo(9.4 * u, 5.5 * u)
                p.lineTo(11.2 * u, 7.6 * u)
                p.lineTo(18.8 * u, 7.6 * u)
                p.quadTo(20.5 * u, 7.6 * u, 20.5 * u, 9.3 * u)
                p.lineTo(20.5 * u, 17.6 * u)
                p.quadTo(20.5 * u, 19.0 * u, 19.0 * u, 19.0 * u)
                p.lineTo(5.0 * u, 19.0 * u)
                p.quadTo(3.5 * u, 19.0 * u, 3.5 * u, 17.5 * u)
                p.closePath()
                g.stroke = stroke(1.7)
                g.draw(p)
            }
            Icon.FILE -> {
                g.stroke = stroke(1.55)
                val p = Path2D.Double()
                p.moveTo(6.2 * u, 4.2 * u)
                p.lineTo(13.9 * u, 4.2 * u)
                p.lineTo(18.2 * u, 8.5 * u)
                p.lineTo(18.2 * u, 19.2 * u)
                p.lineTo(6.2 * u, 19.2 * u)
                p.closePath()
                g.draw(p)
                g.drawLine((13.9 * u).toInt(), (4.2 * u).toInt(), (13.9 * u).toInt(), (8.5 * u).toInt())
                g.drawLine((13.9 * u).toInt(), (8.5 * u).toInt(), (18.2 * u).toInt(), (8.5 * u).toInt())
                g.drawLine((8.5 * u).toInt(), (11.3 * u).toInt(), (15.8 * u).toInt(), (11.3 * u).toInt())
                g.drawLine((8.5 * u).toInt(), (14.5 * u).toInt(), (14.1 * u).toInt(), (14.5 * u).toInt())
            }
            Icon.PLAYLIST -> {
                g.stroke = stroke(1.45)
                for (i in 0..2) {
                    val y = (6.8 + i * 4.7) * u
                    g.drawLine((7.0 * u).toInt(), y.toInt(), (18.4 * u).toInt(), y.toInt())
                    g.fill(RoundRectangle2D.Double(3.8 * u, y - 1.1 * u, 1.8 * u, 1.8 * u, 0.8 * u, 0.8 * u))
                }
            }
            Icon.VOLUME -> {
                g.fill(path(3.0 to 10.0, 7.0 to 10.0, 11.0 to 6.5, 11.0 to 17.5, 7.0 to 14.0, 3.0 to 14.0, close = true))
                g.stroke = stroke(1.5)
                g.draw(Arc2D.Double(9.0 * u, 7.0 * u, 7.5 * u, 10.0 * u, -58.0, 116.0, Arc2D.OPEN))
                g.draw(Arc2D.Double(9.0 * u, 4.5 * u, 12.0 * u, 15.0 * u, -54.0, 108.0, Arc2D.OPEN))
            }
            Icon.DISPLAY -> {
                g.stroke = stroke(1.55)
                g.draw(RoundRectangle2D.Double(3.5 * u, 4.5 * u, 17.0 * u, 12.0 * u, 2.2 * u, 2.2 * u))
                g.drawLine((12 * u).toInt(), (16.8 * u).toInt(), (12 * u).toInt(), (19.1 * u).toInt())
                g.drawLine((8.7 * u).toInt(), (19.3 * u).toInt(), (15.3 * u).toInt(), (19.3 * u).toInt())
            }
            Icon.SUBTITLES -> {
                g.stroke = stroke(1.45)
                g.draw(RoundRectangle2D.Double(3.2 * u, 4.5 * u, 17.6 * u, 13.5 * u, 2.6 * u, 2.6 * u))
                g.drawLine((7 * u).toInt(), (10.2 * u).toInt(), (17 * u).toInt(), (10.2 * u).toInt())
                g.drawLine((7 * u).toInt(), (13.5 * u).toInt(), (14.7 * u).toInt(), (13.5 * u).toInt())
                g.drawLine((7.5 * u).toInt(), (18.0 * u).toInt(), (6.0 * u).toInt(), (20.0 * u).toInt())
            }
            Icon.AUDIO -> {
                g.stroke = stroke(1.7)
                g.drawLine((10.0 * u).toInt(), (6.0 * u).toInt(), (10.0 * u).toInt(), (16.2 * u).toInt())
                g.drawLine((10.0 * u).toInt(), (6.0 * u).toInt(), (18.0 * u).toInt(), (4.3 * u).toInt())
                g.drawLine((18.0 * u).toInt(), (4.3 * u).toInt(), (18.0 * u).toInt(), (14.3 * u).toInt())
                g.fill(Ellipse2D.Double(5.2 * u, 14.1 * u, 5.7 * u, 4.6 * u))
                g.fill(Ellipse2D.Double(13.2 * u, 12.2 * u, 5.7 * u, 4.6 * u))
            }
            Icon.CHAPTERS -> {
                g.stroke = stroke(1.4)
                for (i in 0..2) {
                    val yy = (6.5 + i * 5.3) * u
                    g.fill(Ellipse2D.Double(4.0 * u, yy - 1.1 * u, 2.2 * u, 2.2 * u))
                    g.drawLine((8.2 * u).toInt(), yy.toInt(), (19.4 * u).toInt(), yy.toInt())
                }
            }
            Icon.INFO -> {
                g.stroke = stroke(1.4)
                g.draw(Ellipse2D.Double(4.2 * u, 4.2 * u, 15.6 * u, 15.6 * u))
                g.fill(Ellipse2D.Double(11.0 * u, 7.2 * u, 2.0 * u, 2.0 * u))
                g.fill(RoundRectangle2D.Double(11.0 * u, 10.7 * u, 2.0 * u, 6.2 * u, 1.0 * u, 1.0 * u))
            }
            Icon.LINK -> {
                g.stroke = stroke(1.5)
                g.draw(Arc2D.Double(2.7 * u, 7.2 * u, 10.0 * u, 8.5 * u, 35.0, 250.0, Arc2D.OPEN))
                g.draw(Arc2D.Double(11.3 * u, 7.2 * u, 10.0 * u, 8.5 * u, -145.0, 250.0, Arc2D.OPEN))
                g.drawLine((8.3 * u).toInt(), (12.0 * u).toInt(), (15.7 * u).toInt(), (12.0 * u).toInt())
            }
            Icon.HEART -> {
                val p = Path2D.Double()
                p.moveTo(12.0 * u, 19.2 * u)
                p.curveTo(10.0 * u, 17.3 * u, 4.2 * u, 13.8 * u, 4.2 * u, 8.9 * u)
                p.curveTo(4.2 * u, 5.7 * u, 8.3 * u, 4.0 * u, 12.0 * u, 7.2 * u)
                p.curveTo(15.7 * u, 4.0 * u, 19.8 * u, 5.7 * u, 19.8 * u, 8.9 * u)
                p.curveTo(19.8 * u, 13.8 * u, 14.0 * u, 17.3 * u, 12.0 * u, 19.2 * u)
                p.closePath()
                g.fill(p)
            }
            Icon.MOVE -> {
                g.stroke = stroke(1.55)
                g.drawLine((12.0 * u).toInt(), (4.0 * u).toInt(), (12.0 * u).toInt(), (20.0 * u).toInt())
                g.drawLine((4.0 * u).toInt(), (12.0 * u).toInt(), (20.0 * u).toInt(), (12.0 * u).toInt())
                g.fill(path(12.0 to 2.8, 9.5 to 6.0, 14.5 to 6.0, close = true))
                g.fill(path(12.0 to 21.2, 9.5 to 18.0, 14.5 to 18.0, close = true))
                g.fill(path(2.8 to 12.0, 6.0 to 9.5, 6.0 to 14.5, close = true))
                g.fill(path(21.2 to 12.0, 18.0 to 9.5, 18.0 to 14.5, close = true))
            }
            Icon.CLOSE -> {
                g.stroke = stroke(1.8)
                g.drawLine((6.0 * u).toInt(), (6.0 * u).toInt(), (18.0 * u).toInt(), (18.0 * u).toInt())
                g.drawLine((18.0 * u).toInt(), (6.0 * u).toInt(), (6.0 * u).toInt(), (18.0 * u).toInt())
            }
        }
    }

    private fun registerTexture(image: BufferedImage, prefix: String, rasterScale: Int, logicalPad: Int): CachedTexture {
        val texture = DynamicTexture("MpvCraft $prefix", image.width, image.height, true)
        val pixels = texture.getPixels()
        for (py in 0 until image.height) {
            for (px in 0 until image.width) pixels.setPixel(px, py, image.getRGB(px, py))
        }
        val id = Identifier.fromNamespaceAndPath(
            MpvCraft.MOD_ID,
            "dynamic/${prefix}_${sequence.incrementAndGet()}",
        )
        MpvCraft.mc.getTextureManager().register(id, texture)
        texture.upload()
        return CachedTexture(
            textureId = id,
            textureWidth = image.width,
            textureHeight = image.height,
            drawWidth = ceil(image.width / rasterScale.toDouble()).toInt(),
            drawHeight = ceil(image.height / rasterScale.toDouble()).toInt(),
            drawPad = logicalPad,
        )
    }

    private fun blit(graphics: GuiGraphicsExtractor, entry: CachedTexture, x: Int, y: Int) {
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

    private fun release(entry: CachedTexture) {
        runCatching { MpvCraft.mc.getTextureManager().release(entry.textureId) }
    }

    private fun uiRasterScale(): Int = ceil(MpvCraft.mc.window.guiScale.toDouble()).toInt().coerceIn(2, 8)
    private fun rasterScaleFor(requested: Float): Int = ceil(requested.coerceAtLeast(1f).toDouble()).toInt().coerceIn(1, 8)
    private fun font(size: Int, bold: Boolean): Font = baseFont.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size.toFloat())

    private fun configureGraphics(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }

    private inline fun Graphics2D.useGraphics(block: (Graphics2D) -> Unit) {
        try {
            block(this)
        } finally {
            dispose()
        }
    }
}
