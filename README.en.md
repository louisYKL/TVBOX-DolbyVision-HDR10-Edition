<div align="center">

# TV BOX Dolby Vision / HDR10 Support Edition

> A TVBox branch for Android TV, phones, and tablets, focused on hardware decoding, HDR, subtitles, and bounded streaming caches.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.3.7"><img alt="Release" src="https://img.shields.io/badge/release-v0.3.7-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV%20%2F%20Android-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
  <img alt="HDR" src="https://img.shields.io/badge/HDR-HDR10%20%7C%20HDR10%2B%20%7C%20Dolby%20Vision-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.3.7">Download 0.3.7</a> ·
  <a href="README.md">简体中文</a> ·
  <a href="0.3.7/RELEASE_NOTES.md">Release notes</a>
</p>

</div>

## Downloads

All three variants share playback code and buffering policy, with separate ABI builds. Published packages retain the existing signing certificate; updating the same variant preserves settings and playback history.

| Package | ABI | Devices |
| --- | --- | --- |
| [TVBox_v0.3.7_java32.apk](https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/download/v0.3.7/TVBox_v0.3.7_java32.apk) | `armeabi-v7a` | 32-bit Android TVs, smart screens, and boxes |
| [TVBox_v0.3.7_java64.apk](https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/download/v0.3.7/TVBox_v0.3.7_java64.apk) | `arm64-v8a` | 64-bit phones, tablets, and boxes |
| [TVBox_v0.3.7_hisense32.apk](https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/download/v0.3.7/TVBox_v0.3.7_hisense32.apk) | `armeabi-v7a` | Hisense 32-bit TVs |

Version: `0.3.7`; `versionCode`: `30007`. Checksums accompany the release. Previous changes remain in the [changelog](CHANGELOG.md).

## Changes in 0.3.7

- Fix the Seek loading overlay that could remain at `100%` when vendor player state flags stayed latched. It retains a minimum visible duration and is forcibly cleared at a 30-second upper bound.
- Use the same Seek overlay policy for fullscreen playback and embedded detail playback; the finish callback keeps checking instead of exiting on a transient loading flag.
- Keep the existing system hardware decoder and SurfaceView/HDR route. libdovi/libplacebo frame mapping is not included: decoded frames are not currently exposed to a mapper, and the GPU/HDR output path needs a separately validated prototype.

See the [0.3.7 release notes](0.3.7/RELEASE_NOTES.md) for implementation and validation scope.

## Preview

<p align="center">
  <img src="1.jpg" alt="TVBOX preview 1" width="48%">
  <img src="2.webp" alt="TVBOX preview 2" width="48%">
</p>

## Playback, HDR, and audio

- Video uses hardware Android `MediaCodec` decoders exposed by the device. An app setting cannot add unsupported decoding capabilities.
- HDR classification combines video-track information, PQ/HLG, HDR metadata, and Dolby Vision/HDR10+ markers rather than relying on a filename, bit depth, or wide gamut alone. With playback controls visible, the badge shows the identified video resolution and HDR/SDR state.
- Devices with the necessary decoding and display capabilities retain native Dolby Vision. Other devices use HDR10/HLG/SDR fallback only when the source supplies that compatible base layer. The app requests the appropriate output mode; actual output also depends on firmware, codec, display, and the format profile.
- HDR display brightness remains device-controlled. Unstyled HDR text uses light-gray glyphs and a black outline; ASS/SSA, styled text, and PGS/VobSub bitmap subtitles retain their source presentation. SDR keeps the original subtitle style.
- Audio prefers platform decoders with FFmpeg fallback where needed, retaining stereo PCM output and Android audio routing. These memory changes do not lower audio quality to conceal buffering issues.
- On touch devices, a single tap shows or hides the controls, and a double tap toggles playback. Transport buttons and TV remotes also control playback.

## WebDAV and external subtitles

Save a WebDAV URL, username, and password in Settings, then browse and play remote files from the Home screen's Local entry. An AList HTTP URL can use `http://device-address:5244/dav/`; an HTTPS hostname or IP can use `https://server-address/dav/` (default port `443` when omitted).

Same-name sibling subtitles can load automatically. The external-subtitle dialog also browses other local or WebDAV folders. Supported formats are SRT, ASS/SSA, VTT, TTML, SCC, and STL, with an `8 MiB` read limit. Selecting an external subtitle switches to that subtitle instead of rendering internal and external subtitles together; the selection can be restored on later playback.

Local browsing is limited to directories Android permits the app to read. Use the storage-permission entry in Settings when access is needed; the browser does not bypass system permissions.

## Port 9978 input page

The management server starts with the main application process. Open `http://device-IP:9978/api` from a browser on the same network. If the port is occupied, use the actual address shown in the app.

1. Enter one subscription URL, live URL, WebDAV URL, username, or password in the web page.
2. Confirm that individual field. Its open application input updates immediately; otherwise the value waits in memory until the matching settings dialog opens.
3. Confirm inside the application's settings dialog to save and apply the change. Browser confirmation and Enter do not confirm the app dialog, restart the app, or start playback.

The five fields do not overwrite each other. Empty values can clear a field. Unsaved browser inputs do not survive an application restart.

## Process and memory boundaries

Standalone fullscreen playback and live activities use the `:player` process. The 32-bit TV detail page retains an embedded preview, whose player runs in the detail activity's main process and does not have a separate heap.

Raw Range data, demuxed sample queues, subtitles, images, and UI all consume resources. Cached payloads, metadata, and in-flight blocks in a player process share a heap-based ceiling: up to `192 MiB` on 32-bit and low-heap devices, and `192/288/384 MiB` on high-heap 64-bit devices. Background prefetch also preserves heap headroom, usually `96 MiB` on low-heap devices and `128 MiB` on high-heap devices; minimum-runway admission uses a tighter playback reserve. If actual memory cannot support the required window, a retryable memory message stays on the playback page. A larger video does not cause allocation of the entire file. Internal logs record errors only, rotate automatically, and are bounded to `512 KiB` in total.

## Local build

Build tools, dependencies, and caches use the repository's `_runtime` directory. Prepare the local Android SDK, JDK 11, and existing signing material, then run from the repository root:

```powershell
.\build-local.ps1 -Tasks ':app:assembleNormalRelease'
.\build-local.ps1 -Tasks ':app:assembleJava64Release'
.\build-local.ps1 -Tasks ':app:assembleHisenseRelease'
```

The script selects project-local SDK, Gradle, Android user, and temporary directories. Before publishing, verify each package's version, ABI, historical signing certificate, and SHA-256. The basic GitHub Actions workflow builds debug packages and does not replace release-signature verification.

```text
app/        Application, UI, HDR, subtitles, WebDAV, and management server
player/     Player abstraction, native playback, and Range cache
quickjs/    JS engine module
pyramid/    Python extension module
```

## Community and notes

- [Contributing](CONTRIBUTING.md) · [Support](SUPPORT.md) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md)
- This project bundles no media catalog, live playlist, or subscription source. Use content you are authorized to access.
- `tvOS`, `iOS`, `Apple TV`, `Dolby Vision`, `HDR10`, and `HDR10+` belong to their respective owners.
