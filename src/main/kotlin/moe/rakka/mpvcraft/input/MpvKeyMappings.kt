package moe.rakka.mpvcraft.input

import com.mojang.blaze3d.platform.InputConstants
import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.hud.MpvUi
import moe.rakka.mpvcraft.mpv.MpvPlayer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier

/** Global controls that remain available while playing without opening /mpv. */
object MpvKeyMappings {

    private val category: KeyMapping.Category = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath(MpvCraft.MOD_ID, "controls")
    )

    private val skipIntro: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.mpvcraft.skip_intro",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_N,
            category,
        )
    )

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (skipIntro.consumeClick()) {
                if (!MpvPlayer.hasFile) continue
                val target = MpvPlayer.skipIntro()
                val message = if (target != null) {
                    "[mpv] Skipped intro -> ${target.title.ifBlank { "chapter ${target.index + 1}" }}"
                } else {
                    "[mpv] No intro chapter ahead"
                }
                client.gui.chat.addClientSystemMessage(MpvUi.text(message))
            }
        }
    }
}
