# MpvCraft

MpvCraft embeds **libmpv** as a movable media player inside Minecraft using Fabric.
The video surface, subtitle layer and control UI are rendered in-game without vanilla
Minecraft widgets.

## Features

- Local files and direct media URLs through libmpv.
- Ordinary webpage URLs through mpv's `ytdl_hook` when a compatible `yt-dlp` is available.
- Movable/resizable in-game video surface.
- 0-100% video opacity control.
- Custom playback UI with audio, subtitle and chapter selection.
- Chapter-aware **Skip OP / Skip Intro** action.
- Detached text subtitles with independent scale, position, background and horizontal centre snap.
- Detached bitmap subtitles for local PGS/Blu-ray, VobSub/DVD, DVB and XSUB tracks.
- Bitmap subtitles preserve mpv's original rendering, are cropped to the visible cue for performance,
  and support the same persistent horizontal centre snap as detached text subtitles.
- Native local file picker on Windows/macOS/Linux.

## Commands

```text
/mpv                       open the control menu
/mpv hud                   open the HUD layout editor directly
/mpv play <path-or-url>    load media
/mpv pause                 play / pause
/mpv stop                  stop playback
/mpv subs                  cycle subtitle tracks
/mpv audio                 cycle audio tracks
/mpv volume <0-200>        set mpv volume
/mpv sub <file>            add/select an external subtitle file
/mpv skipintro             skip the detected intro/opening chapter
/mpv cmd <anything>        raw mpv command
```

Navigation is contextual:

- `/mpv` -> **Move UI** -> `Esc` / **Done** returns to the `/mpv` menu.
- `/mpv hud` -> `Esc` / **Done** closes the editor and returns to gameplay.

In the HUD editor, drag the video or a detached subtitle cue to move it and use the
mouse wheel over it to resize. Moving a detached subtitle close to the horizontal
centre shows a guide and enables persistent X-centering for future cues.

## Bitmap subtitle architecture

mpv exposes text subtitle content through `sub-text`, but bitmap formats such as PGS
do not have an equivalent public pixel API. MpvCraft therefore uses a second, muted
libmpv core for supported **local** bitmap tracks. The helper renders the subtitle onto
a transparent RGBA dummy video, then MpvCraft composites only the non-transparent cue
bounds into Minecraft.

The helper is intentionally capped to a small render canvas and a maximum update rate,
so detached PGS does not behave like a second full-resolution video renderer. The main
video remains decoded only by the primary core.

Web/ytdl sources currently keep bitmap subtitles attached because opening a second
resolver/network session cannot reliably guarantee the same track topology.

## Web URLs and yt-dlp

Direct URLs such as `.m3u8`, `.mpd` or `.mp4` can be handed directly to libmpv. A normal
webpage URL is HTML rather than a media stream, so it requires extraction. MpvCraft
enables mpv's `ytdl_hook` and looks for `yt-dlp` in this order:

1. JVM property `-Dmpvcraft.ytdlp=...`
2. `ytDlpPath` in `config/mpvcraft.json`
3. `.minecraft/yt-dlp(.exe)`, `.minecraft/tools/yt-dlp(.exe)`, or `.minecraft/mpvcraft/yt-dlp(.exe)`
4. process `PATH`

Sites unsupported by yt-dlp, DRM-protected sources, or sources requiring site-specific
browser logic are not automatically resolved by MpvCraft.

## Requirements

- Minecraft `26.1.x`
- Fabric Loader + Fabric API + Fabric Language Kotlin
- JDK 25 for building
- A compatible libmpv shared library at runtime

### libmpv

On Windows, make `mpv-2.dll` visible to Java or pass an explicit path:

```text
-Dmpvcraft.libmpv=C:\path\to\mpv-2.dll
```

On Linux/macOS, use the system libmpv package or the same JVM property with an explicit
shared-library path.

## Build

```bash
./gradlew build
```

On Windows:

```bat
gradlew.bat build
```

The development client can be launched with `runClient`.

## Configuration

Runtime settings are stored in:

```text
.minecraft/config/mpvcraft.json
```

The config includes video position/size/opacity, subtitle layout, menu position, volume,
video flip, hardware decoder choice and an optional explicit yt-dlp path.

## Rendering notes

Minecraft's modern GUI pipeline extracts render state before compositing it. MpvCraft
uses `PictureInPictureRenderer` bridges for both video and detached bitmap subtitles.
libmpv renders into owned OpenGL FBOs and the result is copied GPU-to-GPU into the
Minecraft PIP textures; video frames are not copied through system RAM.

The video opacity control rewrites only the alpha channel of the PIP target after the GPU
blit, so changing opacity does not trigger another libmpv render or a CPU-side frame copy.

## License / credits

MpvCraft is distributed under the BSD 3-Clause license. See [LICENSE](LICENSE).

`MpvPipRenderer` and the drag/scroll HUD interaction are adapted from Odin by odtheking
(BSD 3-Clause); attribution is retained in the source and license file.

MpvCraft dynamically links to libmpv. Ensure the mpv build you redistribute or use is
licensed appropriately for your distribution.
