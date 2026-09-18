package moe.rakka.mpvcraft

import moe.rakka.mpvcraft.command.MpvCommand
import moe.rakka.mpvcraft.config.MpvConfig
import moe.rakka.mpvcraft.hud.MpvHud
import moe.rakka.mpvcraft.hud.MpvUi
import moe.rakka.mpvcraft.input.MpvKeyMappings
import moe.rakka.mpvcraft.mpv.MpvBitmapSubtitlePlayer
import moe.rakka.mpvcraft.mpv.MpvPlayer
import moe.rakka.mpvcraft.mpv.MpvPlaylist
import moe.rakka.mpvcraft.render.MpvPipRenderer
import moe.rakka.mpvcraft.render.MpvSubtitlePipRenderer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.resources.Identifier.fromNamespaceAndPath
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

object MpvCraft : ClientModInitializer {

    const val MOD_ID = "mpvcraft"

    val logger: Logger = LogManager.getLogger("MpvCraft")

    @JvmStatic
    val mc: Minecraft = Minecraft.getInstance()

    lateinit var config: MpvConfig
        private set

    private val HUD_LAYER: Identifier = fromNamespaceAndPath(MOD_ID, "video")

    override fun onInitializeClient() {
        config = MpvConfig.load()
        MpvPlayer.flipY = config.flipY
        MpvPlayer.hwdec = config.hwdec

        PictureInPictureRendererRegistry.register { context ->
            MpvPipRenderer(context.bufferSource())
        }
        PictureInPictureRendererRegistry.register { context ->
            MpvSubtitlePipRenderer(context.bufferSource())
        }

        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, HUD_LAYER, MpvHud::render)

        MpvPlaylist.initializeFromConfig()
        MpvCommand.register()
        MpvKeyMappings.register()
        ClientTickEvents.END_CLIENT_TICK.register {
            MpvCommand.tick()
            MpvPlaylist.tick()
        }

        // libmpv is NOT initialized here. mpv_render_context_create needs the
        // OpenGL context to be current, and it wants a real window, so we defer
        // until the first /mpv play. See MpvPlayer.init().

        ClientLifecycleEvents.CLIENT_STOPPING.register {
            config.save()
            MpvUi.clearCache()
            MpvBitmapSubtitlePlayer.shutdown()
            MpvPlayer.shutdown()
        }

        logger.info("MpvCraft ready. Use /mpv to open the control menu.")
    }
}
