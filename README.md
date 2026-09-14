# MpvCraft

MpvCraft embeds libmpv as a movable in-game video player for Minecraft 26.1.2 / Fabric.
Subtitles are rendered by Minecraft itself and can either follow the video or be detached
and positioned independently.

## Commands

```text
/mpv                       open the control menu
/mpv hud                   open the mouse-only HUD layout editor
/mpv play <path or url>    load media
/mpv pause                 play / pause
/mpv stop                  stop playback
/mpv subs                  cycle subtitle tracks
/mpv audio                 cycle audio tracks
/mpv volume <0-200>        set mpv volume
/mpv sub <file>            add/select an external subtitle file
/mpv cmd <anything>        raw mpv command
```

The HUD editor intentionally has no custom keyboard shortcuts. Drag the video or detached
subtitle box to move it and use the mouse wheel over a box to resize it. `Esc` and `Done`
save the layout and return to the MpvCraft control menu.

The control menu itself can be dragged by its title bar. Volume uses a continuous 0–200%
slider and audio/subtitle tracks use dropdown selectors.

## Rendering architecture

Minecraft 26.x extracts GUI render states and composites them later, so MpvCraft uses a
`PictureInPictureRenderer` bridge rather than issuing raw OpenGL from the HUD callback.

```text
libmpv (vo=libmpv, software decode by default)
        |
        | mpv_render_context_render()
        v
owned RGBA8 OpenGL FBO
        |
        | glBlitFramebuffer (GPU -> GPU)
        v
Minecraft PIP texture
        |
        v
GUI compositor
```

There is no frame copy through system RAM.

The video layout is stored in window-pixel coordinates so it stays visually stable when
Minecraft GUI scale changes. Before a PIP render state is queued, that physical rectangle
is transformed through the current GUI pose into final GUI coordinates. This avoids the
old double application of `guiScale` that made a 840x472 HUD box allocate/render as
1680x944 at GUI scale 2.

libmpv's OpenGL renderer is isolated with `GlStateGuard`: Minecraft state is saved,
libmpv receives a near-default GL state, and the exact state is restored afterward.
libmpv renders only when its update callback reports a new video/display frame; normal
Minecraft frames only perform the final framebuffer blit.

`mpv_render_context_render()` normally waits until the video's presentation timestamp,
which can cap the caller's render loop to the video frame rate. MpvCraft disables that
wait with `MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME=0` and `video-timing-offset=0`, so a
24/30/60 fps video does not become Minecraft's FPS limiter.

## Text / subtitles

mpv uses UTF-8 for its client API. MpvCraft explicitly configures JNA for UTF-8 and also
uses UTF-8 for native pointer reads and command arrays. This avoids Windows-default-codepage
mojibake such as `Jâ€™ai` in subtitle text, media titles, track names and paths.

mpv runs with `sub-visibility=no`. MpvCraft observes mpv's `sub-text` property and draws
that text itself so subtitles can be detached from the video, scaled independently and
optionally given a background.

MpvCraft UI text and subtitles use Minecraft's bundled `minecraft:uniform` font resource.
It has broad Unicode coverage (including accented Latin text) without shipping a separate
third-party font file.

## Setup

1. Install a libmpv shared library.
   - Windows: use a recent shinchiro build and make `mpv-2.dll` visible to Java, or pass
     `-Dmpvcraft.libmpv=C:\\path\\to\\mpv-2.dll`.
   - Linux/macOS: use the normal system libmpv package or pass an explicit library path.
2. Use JDK 25.
3. Build/run:

```bat
set JAVA_HOME=C:\Users\<user>\AppData\Roaming\PrismLauncher\java\java-runtime-epsilon
set PATH=%JAVA_HOME%\bin;%PATH%
gradlew.bat runClient
```

Optional libmpv debug log:

```text
-Dmpvcraft.mpvLog=run/mpv.log
```

Fatal JVM native crashes are configured to write to `run/hs_err_%p.log` in the dev run.

## Runtime notes

- Audio is owned by mpv and is not spatialized through Minecraft's audio mixer.
- `hwdec=no` is the safe default for the Windows desktop-WGL integration. If software
  decoding becomes a bottleneck, a copy-back decoder such as `d3d11va-copy` is safer to
  experiment with than a direct GL/D3D interop path.
- The video HUD is hidden with Minecraft's normal F1 GUI hide behavior.

## Credits / licensing

`MpvPipRenderer` and the drag/scroll HUD interaction are adapted from Odin by odtheking
(BSD 3-Clause). Keep the attribution in `LICENSE` when redistributing.

MpvCraft dynamically links to libmpv. libmpv is LGPL in a standard compatible build;
do not bundle a GPL-configured mpv build unless your redistribution complies with it.

### V5 UI controls

`/mpv` opens the movable control panel. Use **Open file…** to choose local media without typing a command; `/mpv play <path-or-url>` is still useful for URLs. The panel uses a clean runtime system sans-serif instead of Minecraft's bitmap font.

`/mpv hud` opens only the HUD layout editor. **Esc** or **Done** saves and closes it directly. When subtitles are detached, dragging them shows a vertical centre guide and snaps their horizontal centre to the middle of the screen when close enough; vertical positioning remains free.
