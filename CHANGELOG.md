# Changelog

All notable changes to **MpvCraft** will be documented in this file.

MpvCraft is currently in alpha. Features listed under **Unreleased / Upcoming** are planned and may change before release.

---

## [Unreleased]

### Upcoming

- **Playlist support**
  - Allow opening a folder as a playlist source.
  - Automatically add supported media files from the selected folder.
  - Sort playlist entries by:
    - alphabetical order
    - file date
  - Navigate between playlist entries from the MpvCraft interface.

- **Clickable timeline / seek bar**
  - Add a playback timeline directly to the `/mpv` menu.
  - Click anywhere on the timeline to seek to that position.
  - Keep the timeline synchronized with the current playback position and media duration.

- **Manual subtitle loading**
  - Add an option in the MpvCraft menu to manually load an external subtitle file.
  - Allow adding subtitles even when the current media already contains embedded subtitle tracks.
  - Make the newly added subtitle track available through the normal subtitle selector.

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
- Independent positioning, scaling, and center snapping for detached bitmap subtitles.
- Experimental Skip Intro / Skip OP support based on chapter metadata.
- Context-aware HUD editor navigation:
  - `/mpv` -> `Move UI` -> `Esc` / `Done` returns to the MpvCraft menu.
  - `/mpv hud` -> `Esc` / `Done` returns directly to gameplay.
- Windows, Linux, and macOS libmpv loading support.
- Explicit libmpv path support through:

  ```text
  -Dmpvcraft.libmpv=/path/to/libmpv
  ```

- Optional yt-dlp path configuration and automatic lookup.
- Config persistence for video, subtitle, HUD, volume, opacity, and playback-related settings.

### Experimental

The following features are functional but still considered experimental:

- **Detached bitmap / Blu-ray PGS subtitles**
- **Skip Intro / Skip OP**

Detached bitmap subtitle compatibility may vary depending on the media file, subtitle codec, operating system, GPU/driver, and libmpv build.

Skip Intro currently relies on chapter metadata and may select the wrong section when chapters are missing, unnamed, or structured differently.

### Known limitations

- Some unusual subtitle tracks may render incorrectly.
- Detached bitmap subtitle support is primarily intended for local media files.
- Webpage URL compatibility depends on mpv and yt-dlp.
- DRM-protected media is not supported.
- Some media files may expose unusual track or chapter metadata.
- Performance and compatibility may vary depending on the operating system, GPU, drivers, and libmpv build.
- libmpv is currently not bundled with MpvCraft and must be installed separately.

---

[Unreleased]: https://github.com/Shoukshai/MpvCraft/compare/v0.1.0-alpha.1...HEAD
[0.1.0-alpha.1]: https://github.com/Shoukshai/MpvCraft/releases/tag/v0.1.0-alpha.1
