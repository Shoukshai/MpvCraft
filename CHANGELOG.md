# Changelog

All notable changes to **MpvCraft** are documented here.

## [0.1.0-alpha.2] - Unreleased

Alpha 2 focuses on playback navigation and day-to-day media handling while keeping the existing libmpv rendering and detached subtitle architecture intact.

### Added

- Folder-backed playlists through **Open Media**.
  - Select a file to play it directly.
  - Select a folder to build a playlist from supported media files in that folder.
- Playlist browser in the right-side tab area.
- Playlist sorting by:
  - file name
  - modification date
  - ascending or descending order
- Optional automatic playback of the next playlist entry when the current file reaches natural EOF.
- Previous / Next playlist controls.
- Clickable and draggable playback timeline.
- Chapter markers on the timeline when chapter metadata is available.
- Manual external subtitle loading from the MpvCraft menu.
- Minecraft key bindings under the **MpvCraft** controls category:
  - Left Arrow: seek backward 5 seconds
  - Right Arrow: seek forward 5 seconds
  - K: Play / Pause

### Changed

- The left side of the `/mpv` menu is now independently scrollable.
- The right-side browser is independently scrollable and remembers a separate scroll position for Subtitles, Audio, Chapters and Playlist.
- `Open File` has been replaced by **Open Media**, with File / Folder selection.
- Playback seek buttons now use 5-second jumps to match the default key bindings.
- Media Information has moved from a permanent card to a retractable side drawer.
- Playlist options are available directly from the left settings column.

### Still experimental

- Detached bitmap / Blu-ray PGS subtitles.
- Skip Intro / Skip OP chapter detection.

### Planned after Alpha 2

- Theme / color customization in a dedicated appearance screen.
- Additional UI customization once the previous color-picker implementation is recovered and adapted.

---

## [0.1.0-alpha.1]

First public alpha release of MpvCraft.

### Added

- libmpv-powered video playback directly inside Minecraft.
- Local media file playback.
- Direct media URL playback.
- Optional yt-dlp integration for supported webpage URLs.
- Native system file picker.
- Fully custom in-game media control interface.
- `/mpv` main media menu.
- `/mpv hud` direct HUD editor access.
- Movable and resizable video HUD.
- Adjustable video opacity.
- Volume control from 0% to 200%.
- Video visibility toggle.
- Vertical video flip.
- Audio track selection.
- Subtitle track selection.
- Chapter browser.
- Detached text subtitle rendering.
- Independent subtitle positioning and scaling.
- Horizontal center snapping for detached subtitles.
- Optional subtitle background.
- Experimental detached bitmap subtitle support, including Blu-ray PGS.
- Cropped bitmap subtitle rendering to reduce unnecessary GPU compositing.
- Independent positioning, scaling and center snapping for detached bitmap subtitles.
- Experimental Skip Intro / Skip OP support based on chapter metadata.
- Context-aware HUD editor navigation:
  - `/mpv` -> `Move UI` -> `Esc` / `Done` returns to the MpvCraft menu.
  - `/mpv hud` -> `Esc` / `Done` returns directly to gameplay.
- Windows, Linux and macOS libmpv loading support.
- Optional yt-dlp path configuration and automatic lookup.
- Persistent video, subtitle, HUD, volume, opacity and playback settings.

### Known limitations

- Detached bitmap subtitles are experimental and primarily intended for local media files.
- Skip Intro / Skip OP is experimental.
- Some unusual subtitle tracks may render incorrectly.
- Webpage URL compatibility depends on mpv and yt-dlp.
- DRM-protected media is not supported.
- Performance and compatibility may vary by operating system, GPU, driver and libmpv build.
- libmpv is not bundled with MpvCraft and must be installed separately.

[0.1.0-alpha.2]: https://github.com/Shoukshai/MpvCraft/compare/v0.1.0-alpha.1...HEAD
[0.1.0-alpha.1]: https://github.com/Shoukshai/MpvCraft/releases/tag/v0.1.0-alpha.1
