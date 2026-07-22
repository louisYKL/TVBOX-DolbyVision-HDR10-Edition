<div align="center">

# TV BOX Dolby Vision / HDR10 Support Edition

> A TV-first TVBox branch focused on native hardware playback, HDR activation, Dolby Vision fallback routing, and living-room friendly interaction.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.4"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.4-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV%20%2F%20Android-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
  <img alt="HDR" src="https://img.shields.io/badge/HDR-HDR10%20%7C%20HDR10%2B%20%7C%20DV%20fallback-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.4">Download 0.2.4</a> ·
  <a href="README.md">简体中文</a>
</p>

</div>

## In one line

This branch is built for real living-room playback: keep native system decoding whenever the device can do it well, route unstable MKV / WebM / Dolby Vision cases to the built-in compatibility path, and keep HDR, subtitles, audio passthrough, and remote interaction working together.

## Downloads

| File | Target devices | Notes |
| --- | --- | --- |
| `TVBox_v0.2.4_java32.apk` | Mainstream 32-bit Android TVs / smart screens | Primary TV build, compatible with in-place updates from 0.2.3 and earlier test builds |
| `TVBox_v0.2.4_java64.apk` | 64-bit Android phones / tablets / boxes | Dedicated 64-bit build, compatible with in-place updates from 0.2.3 and earlier test builds |
| `TVBox_v0.2.4_hisense32.apk` | Hisense 32-bit TVs | Vendor-specific build, compatible with in-place updates from 0.2.3 |

## Preview

<p align="center">
  <img src="1.jpg" alt="TVBOX preview 1" width="48%">
  <img src="2.webp" alt="TVBOX preview 2" width="48%">
</p>

## What this project is

This is not a “just make it play somehow” TVBox fork.

The branch is built around real TV use cases: native playback when the device can do it well, a compatibility path when vendor firmware cannot, proper HDR routing, subtitle handling, audio passthrough behavior, and remote-control friendly fullscreen interaction.

The core principle is simple:

- If the device system player can handle the stream reliably, keep it on the native path.
- If the native chain is unstable for the container or stream type, route it to the built-in compatibility player.
- Detect HDR10, HDR10+, and Dolby Vision from the actual video stream, not from titles or filenames.

## What this branch focuses on

- Preserve the native hardware decode path for standard HDR10 / HDR10+ playback.
- Add a more reliable compatibility chain for MKV / WebM / difficult Dolby Vision cases.
- Prefer HDR10 base-layer playback on devices without native Dolby Vision decoding.
- Keep subtitles, audio passthrough, fullscreen controls, and remote focus behavior consistent for TV use.
- Split the project into clearer deliverables for long-term maintenance.

## 0.2.4 Variants

| Variant | ABI | Target devices | Notes |
| --- | --- | --- | --- |
| `java32` | `armeabi-v7a` | Mainstream 32-bit Android TVs / smart screens | Primary TV build |
| `java64` | `arm64-v8a` | 64-bit Android phones / tablets / boxes | Dedicated 64-bit build |
| `hisense` | `armeabi-v7a` | Hisense 32-bit TVs | Dedicated Hisense build |

## What 0.2.4 focused on

- Fixes Java64 live playback that could keep audio while showing a black frame: the old system player now detaches its render Surface before release, and retry/channel switching cannot release it twice. The system player retains the system URL; only the compatibility player uses the compatibility URL.
- Ordinary non-Dolby-Vision VOD on the main 32-bit and Hisense variants now stays on Android `MediaPlayer` hardware decoding rather than silently falling back to the compatibility player. Dolby Vision keeps its dedicated native or compatibility route.
- Saved-progress startup has its own range-seek allowance. Sustained native buffering can defer the outer safety net only up to 180 seconds, preventing healthy first-frame decoding or repeated seeks from being timed out.
- All three variants retain the Java64 touch/gesture/focus, subtitle, and audio paths plus the Hisense package/ABI boundary. Version is `0.2.4` (`versionCode 2031`) using the existing signing certificate, installable in place over `0.2.3` and earlier builds.

## Highlights

### 1. Native system-player first

- Standard MP4 / TS / HDR10 / HDR10+ playback prefers the native system player.
- This keeps hardware decoding, native HDR switching, and vendor image processing in the device path whenever possible.

### 2. Dolby Vision routing

- The app probes the stream before playback and selects the playback path up front.
- On devices without native DV decoding:
- If an HDR10 base layer is available, the app prefers that path.
- If not, it falls back to the built-in compatibility player with HDR or SDR fallback depending on device capability.

### 3. MKV / WebM compatibility

- Some TV firmware is unreliable with HTTP HEVC MKV on the native extractor / decoder chain.
- The built-in MPV compatibility path is used to avoid forcing those streams through a path that simply fails.

### 4. Subtitles and audio

- Supports internal subtitles, source subtitles, external subtitles, and local subtitles.
- Automatically prefers Simplified Chinese / Traditional Chinese when available.
- Audio passthrough follows the app setting while the app keeps its own volume at full scale.

### 5. TV-first interaction

- The UI and control flow are tuned for remote navigation, fullscreen control layers, seek behavior, and predictable back handling.
- The visual direction keeps a dark base with liquid-glass style components and a tvOS-inspired UI foundation.

## Why this branch exists

Many TVBox forks put most of the work into source integration, but the playback chain itself is often treated as an afterthought:

- HDR detection is guessed from titles.
- MKV / WebM is forced into the native player even when vendor firmware cannot handle it.
- Audio passthrough, subtitles, fullscreen controls, and remote focus fight each other.
- 32-bit TV, 64-bit Android, and vendor-specific device differences are all mixed into one maintenance path.

This repository is meant to make the playback stack and TV experience more deliberate, not just more crowded.

## Playback architecture

```text
Stream probe
  -> identify HDR10 / HDR10+ / Dolby Vision
  -> inspect container and device capability
  -> choose native system player or compatibility player
  -> apply HDR request, subtitle policy, audio passthrough, and fullscreen controls
```

## Repository layout

```text
app/        Main Android application
player/     Player abstraction and native playback logic
quickjs/    JS engine module
pyramid/    Python extension module
```

## Local build

The repository is arranged so build dependencies and caches can stay inside the project runtime directory.

- Android SDK: `E:\tvbox\TVBoxOS-main\_runtime\android-sdk`
- JDK: `E:\tvbox\TVBoxOS-main\_runtime\jdk\temurin11\jdk-11.0.31+11`
- Gradle Home: `E:\tvbox\TVBoxOS-main\_runtime\gradle-home`

PowerShell:

```powershell
$env:JAVA_HOME='E:\tvbox\TVBoxOS-main\_runtime\jdk\temurin11\jdk-11.0.31+11'
$env:GRADLE_USER_HOME='E:\tvbox\TVBoxOS-main\_runtime\gradle-home'

.\gradlew.bat :app:assembleNormalDebug
.\gradlew.bat :app:assembleJava64Debug
.\gradlew.bat :app:assembleHisenseDebug
```

## GitHub Actions

The repository includes a basic Android build workflow for:

- `TVBox_v0.2.4_java32.apk`
- `TVBox_v0.2.4_java64.apk`
- `TVBox_v0.2.4_hisense32.apk`

## Community

- [Contributing Guide](CONTRIBUTING.md)
- [Support](SUPPORT.md)
- [Security Policy](SECURITY.md)
- [Changelog](CHANGELOG.md)

## Who this is for

- Anyone who wants to preserve native hardware decoding and HDR activation on Android TV or smart-screen devices.
- Anyone who needs separate delivery tracks for 32-bit TV, 64-bit Android, and vendor-specific builds.
- Anyone who wants to treat the TV playback stack as a product surface, not just a transport layer for content sources.

## Notes

- This project does not bundle any media catalog, live playlist, or subscription source.
- Only use content sources, subtitles, and subscriptions that you are legally allowed to use.
- `tvOS`, `iOS`, `Apple TV`, `Dolby Vision`, `HDR10`, and `HDR10+` are trademarks of their respective owners.

## Roadmap

- `0.1`: unify the 32-bit TV build, 64-bit Android build, and Hisense 32-bit build.
- `0.1.1`: continue closing HDR / DV probe issues, playback routing edge cases, progress persistence, and source-switch state isolation.
- `0.1.3`: tighten the 64-bit system-player render/touch path and repack the three Android release variants.
- `0.1.9.1`: publish the three source trees and APKs through one GitHub Release.
- `0.2.0`: fix 32-bit system-player black-screen-with-audio, playback-failure, and player-error risks while preserving in-place updates from `0.1.9.1`.
- `0.2.1`: fix Wen Cai HLS black-screen-with-audio / fake-first-frame / false-error cases, unify the `SurfaceView` hardware-decode path, and fail unsupported `H.264 High10` streams explicitly.
- `0.2.2`: rebuild prebuffering and seek coordination, restore stable audio/subtitle/progress behavior, add buffering percentage, fullscreen subtitle controls, and the detail-page filename, then sync all three variants.
- `0.2.3`: fix the root cause of playback/seek/scrub freezing into "播放超时" by adding a finite per-read inactivity timeout to every proxy streaming client (local direct stream, HLS/live `ts` relay, foreign `go=stream` passthrough, and m3u8 fetch), and treat active native buffering itself as a liveness signal so healthy playback is no longer killed while its byte-count percentage plateaus.
- `0.2.4`: fix Java64 live black-screen-with-audio and the Surface lifecycle conflict during player release; keep ordinary non-Dolby-Vision VOD on the native system decoder for main 32-bit and Hisense; give saved-progress startup and repeated timeline movement a bounded native-buffer allowance to avoid false timeouts.
- Next: keep closing playback, subtitle, HDR, and audio behavior from device logs.
