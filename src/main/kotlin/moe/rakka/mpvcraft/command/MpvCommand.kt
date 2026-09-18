package moe.rakka.mpvcraft.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import moe.rakka.mpvcraft.MpvCraft
import moe.rakka.mpvcraft.MpvCraft.mc
import moe.rakka.mpvcraft.hud.MpvHudScreen
import moe.rakka.mpvcraft.hud.MpvMenuScreen
import moe.rakka.mpvcraft.hud.MpvUi
import moe.rakka.mpvcraft.mpv.MpvPlayer
import moe.rakka.mpvcraft.mpv.MpvPlaylist
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

object MpvCommand {

    /**
     * A screen cannot be opened straight from a command: we are still inside the
     * chat screen's teardown, and Minecraft would close ours immediately. So the
     * request is parked here and consumed on the next client tick.
     */
    @Volatile
    var pendingOpen: Boolean = false

    /** Which screen the pending open should show. */
    @Volatile
    var pendingHud: Boolean = false

    // Brigadier builders typed to Fabric's client command source, built directly
    // rather than through ClientCommandManager so there is one less Fabric API
    // surface to track across versions.
    private fun lit(name: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        LiteralArgumentBuilder.literal(name)

    private fun <T> arg(
        name: String,
        type: ArgumentType<T>,
    ): RequiredArgumentBuilder<FabricClientCommandSource, T> =
        RequiredArgumentBuilder.argument(name, type)

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                lit("mpv")
                    .executes { pendingOpen = true; pendingHud = false; 1 }
                    .then(lit("hud").executes { pendingOpen = true; pendingHud = true; 1 })
                    .then(
                        lit("play").then(
                            arg("path", StringArgumentType.greedyString()).executes { ctx ->
                                val path = StringArgumentType.getString(ctx, "path").trim().trim('"')
                                if (!MpvPlayer.init()) {
                                    say("libmpv could not be loaded: ${MpvPlayer.failureReason}")
                                } else {
                                    MpvPlaylist.clear()
                                    MpvPlayer.load(path)
                                    MpvPlayer.setVolume(MpvCraft.config.volume)
                                    say("Playing $path")
                                }
                                1
                            }
                        )
                    )
                    .then(lit("pause").executes { MpvPlayer.togglePause(); 1 })
                    .then(lit("stop").executes { MpvPlayer.stop(); 1 })
                    .then(lit("skipintro").executes {
                        val target = MpvPlayer.skipIntro()
                        say(
                            if (target != null) "Skipped intro -> ${target.title.ifBlank { "chapter ${target.index + 1}" }}"
                            else "No intro chapter ahead"
                        )
                        1
                    })
                    .then(lit("subs").executes { MpvPlayer.cycleSubTrack(); say("Cycled subtitle track"); 1 })
                    .then(lit("audio").executes { MpvPlayer.cycleAudioTrack(); say("Cycled audio track"); 1 })
                    .then(
                        lit("volume").then(
                            arg("value", IntegerArgumentType.integer(0, 200)).executes { ctx ->
                                val v = IntegerArgumentType.getInteger(ctx, "value")
                                MpvCraft.config.volume = v
                                MpvCraft.config.save()
                                MpvPlayer.setVolume(v)
                                say("Volume $v")
                                1
                            }
                        )
                    )
                    .then(
                        lit("sub").then(
                            arg("file", StringArgumentType.greedyString()).executes { ctx ->
                                val f = StringArgumentType.getString(ctx, "file").trim().trim('"')
                                if (MpvPlayer.addSubtitle(f)) say("Loaded subtitle file")
                                else say("Could not load subtitle file")
                                1
                            }
                        )
                    )
                    .then(
                        // Escape hatch: anything mpv understands, e.g. /mpv cmd set speed 1.5
                        lit("cmd").then(
                            arg("args", StringArgumentType.greedyString()).executes { ctx ->
                                val parts = StringArgumentType.getString(ctx, "args")
                                    .split(' ')
                                    .filter { it.isNotBlank() }
                                MpvPlayer.command(*parts.toTypedArray())
                                1
                            }
                        )
                    )
            )
        }
    }

    /** Called once per client tick from the mod entrypoint. */
    fun tick() {
        if (pendingOpen) {
            pendingOpen = false
            if (pendingHud) MpvHudScreen.openStandalone() else mc.setScreen(MpvMenuScreen)
        }
    }

    private fun say(msg: String) {
        val lines = msg.split('\n')
        mc.schedule {
            lines.forEach { mc.gui.chat.addClientSystemMessage(MpvUi.text("[mpv] $it")) }
        }
    }
}
