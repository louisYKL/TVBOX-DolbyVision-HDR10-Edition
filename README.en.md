<div align="center">

# TV BOX Dolby Vision / HDR10 Support Edition

> A TV-first TVBox branch focused on native hardware playback, HDR activation, Dolby Vision fallback routing, and living-room friendly interaction.

<p>
  <a href="https://github.com/louisYKL/TV-BOX-DolbyVision-HDR10-Edition/releases/tag/v0.1.1"><img alt="Release" src="https://img.shields.io/badge/release-v0.1.1-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV%20%2F%20Android-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
  <img alt="HDR" src="https://img.shields.io/badge/HDR-HDR10%20%7C%20HDR10%2B%20%7C%20DV%20fallback-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

<p>
  <a href="https://github.com/louisYKL/TV-BOX-DolbyVision-HDR10-Edition/releases/tag/v0.1.1">Download 0.1.1</a> ·
  <a href="README.md">简体中文</a>
</p>

</div>

## In one line

This branch is built for real living-room playback: keep native system decoding whenever the device can do it well, route unstable MKV / WebM / Dolby Vision cases to the built-in compatibility path, and keep HDR, subtitles, audio passthrough, and remote interaction working together.

## Downloads

| File | Target devices | Notes |
| --- | --- | --- |
| `TVBox_v0.1.1_java32.apk` | Mainstream 32-bit Android TVs / smart screens | Primary TV build |
| `TVBox_v0.1.1_java64.apk` | 64-bit Android phones / tablets / boxes | Dedicated 64-bit build |
| `TVBox_v0.1.1_hisense32.apk` | Hisense 32-bit TVs | Vendor-specific build |

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

## 0.1.1 Variants

| Variant | ABI | Target devices | Notes |
| --- | --- | --- | --- |
| `java32` | `armeabi-v7a` | Mainstream 32-bit Android TVs / smart screens | Primary TV build |
| `java64` | `arm64-v8a` | 64-bit Android phones / tablets / boxes | Dedicated 64-bit build |
| `hisense` | `armeabi-v7a` | Hisense 32-bit TVs | Dedicated Hisense build |

## What 0.1.1 focused on

- Tightened HDR / Dolby Vision routing so local-proxy MKV streams do not fall back to SDR too easily after a probe timeout.
- Improved local `proxy/play` byte-level preflight so MKV / HDR / DV streams can be classified before the native route is chosen.
- Fixed playback progress and history-key drift by unifying `sourceKey` and `progressKey` resolution across embedded and activity playback flows.
- Added stronger generation isolation in `PlayActivity` so old WebView sniffing, m3u8 callbacks, and source-switch results cannot leak into a newer playback request.
- Kept the overall strategy intact: system player first when it is trustworthy, MPV compatibility path when the native chain is not.

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

- Android SDK: `E:\apk\tvbox\TVBoxOS-main\_runtime\android-sdk`
- JDK: `E:\apk\tvbox\TVBoxOS-main\_runtime\jdk\temurin11\jdk-11.0.31+11`
- Gradle Home: `E:\apk\tvbox\TVBoxOS-main\_runtime\gradle-home`

PowerShell:

```powershell
$env:JAVA_HOME='E:\apk\tvbox\TVBoxOS-main\_runtime\jdk\temurin11\jdk-11.0.31+11'
$env:GRADLE_USER_HOME='E:\apk\tvbox\TVBoxOS-main\_runtime\gradle-home'

.\gradlew.bat :app:assembleNormalDebug
.\gradlew.bat :app:assembleJava64Debug
.\gradlew.bat :app:assembleHisenseDebug
```

## GitHub Actions

The repository includes a basic Android build workflow for:

- `TVBox_debug-java32.apk`
- `TVBox_debug-java64.apk`
- `TVBox_debug-hisense.apk`

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
- Next: portable Windows build, more device-specific branches, and a proper GitHub Release workflow.
