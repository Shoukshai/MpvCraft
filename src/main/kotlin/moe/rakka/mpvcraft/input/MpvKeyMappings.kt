package moe.rakka.mpvcraft.input

import com.mojang.blaze3d.platform.InputConstants
import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.mpv.MpvPlayer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier

object MpvKeyMappings {

    private val category: KeyMapping.Category = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath(MpvCraft.MOD_ID, "controls")
    )

    private val seekBackward: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.mpvcraft.seek_backward",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_LEFT,
            category,
        )
    )

    private val seekForward: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.mpvcraft.seek_forward",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_RIGHT,
            category,
        )
    )

    private val playPause: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.mpvcraft.play_pause",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            category,
        )
    )

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register tick@{ client ->
            if (client.screen != null) return@tick
            while (seekBackward.consumeClick()) {
                if (MpvPlayer.hasFile) MpvPlayer.seek(-5)
            }
            while (seekForward.consumeClick()) {
                if (MpvPlayer.hasFile) MpvPlayer.seek(5)
            }
            while (playPause.consumeClick()) {
                if (MpvPlayer.hasFile) MpvPlayer.togglePause()
            }
        }
    }
}
