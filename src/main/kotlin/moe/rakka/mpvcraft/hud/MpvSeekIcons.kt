package moe.rakka.mpvcraft.hud

import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal object MpvSeekIcons {
    enum class Direction(val resourceName: String) {
        REWIND("rewind-5"),
        FORWARD("forward-5"),
    }

    private val masks: Map<Direction, BufferedImage> by lazy {
        Direction.entries.associateWith { direction ->
            val path = "/assets/mpvcraft/textures/gui/icons/${direction.resourceName}.png"
            val stream = checkNotNull(MpvSeekIcons::class.java.getResourceAsStream(path)) {
                "Missing MpvCraft seek icon resource: $path"
            }
            val image = stream.use { checkNotNull(ImageIO.read(it)) { "Invalid seek icon PNG: $path" } }
            check(image.width == image.height && image.colorModel.hasAlpha()) {
                "Seek icon must be a square RGBA image: $path"
            }
            image
        }
    }

    fun rasterize(direction: Direction, pixelSize: Int, argb: Int): BufferedImage {
        require(pixelSize in 1..4096) { "Invalid seek icon size: $pixelSize" }
        val mask = resize(checkNotNull(masks[direction]), pixelSize)
        val pixels = mask.getRGB(0, 0, pixelSize, pixelSize, null, 0, pixelSize)
        val opacity = argb ushr 24
        val rgb = argb and 0x00FFFFFF
        for (index in pixels.indices) {
            val alpha = ((pixels[index] ushr 24) * opacity + 127) / 255
            pixels[index] = if (alpha == 0) 0 else (alpha shl 24) or rgb
        }
        return BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, pixelSize, pixelSize, pixels, 0, pixelSize)
        }
    }

    private fun resize(source: BufferedImage, pixelSize: Int): BufferedImage {
        var image = source
        while (image.width != pixelSize) {
            val nextSize = if (image.width > pixelSize) maxOf(pixelSize, image.width / 2) else pixelSize
            val next = BufferedImage(nextSize, nextSize, BufferedImage.TYPE_INT_ARGB)
            val graphics = next.createGraphics()
            try {
                graphics.composite = AlphaComposite.Src
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.drawImage(image, 0, 0, nextSize, nextSize, null)
            } finally {
                graphics.dispose()
            }
            image = next
        }
        return image
    }
}
