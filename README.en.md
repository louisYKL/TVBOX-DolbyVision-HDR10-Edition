<div align="center">

# TV BOX Dolby Vision / HDR10 Support Edition

> A TV-first TVBox branch focused on device hardware decoding, HDR activation, capability-aware Dolby Vision routing, and living-room friendly interaction.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.7"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.7-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV%20%2F%20Android-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
  <img alt="HDR" src="https://img.shields.io/badge/HDR-HDR10%20%7C%20HDR10%2B%20%7C%20DV%20fallback-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.7">Download 0.2.7</a> ·
  <a href="README.md">简体中文</a>
</p>

</div>

## In one line

This branch is built for real living-room playback: every video uses the current device's Android `MediaCodec` hardware path, audio prefers platform decoding and ends as stereo PCM, and HDR, subtitles, sustained buffering, and remote interaction stay in one pipeline.

## Downloads

| File | Target devices | Notes |
| --- | --- | --- |
| `TVBox_v0.2.7_java32.apk` | Mainstream 32-bit Android TVs / smart screens | Primary TV build, installable over 0.2.6 and earlier builds |
| `TVBox_v0.2.7_java64.apk` | 64-bit Android phones / tablets / boxes | Dedicated 64-bit build, installable over 0.2.6 and earlier builds |
| `TVBox_v0.2.7_hisense32.apk` | Hisense 32-bit TVs | Vendor-specific build, installable over 0.2.6 and earlier builds |

## Preview

<p align="center">
  <img src="1.jpg" alt="TVBOX preview 1" width="48%">
  <img src="2.webp" alt="TVBOX preview 2" width="48%">
</p>

## What this project is

This is not a “just make it play somehow” TVBox fork.

The branch is built around real TV use cases: device hardware video decoding, capability-aware HDR routing, reliable stereo PCM audio, subtitle handling, and remote-control friendly fullscreen interaction.

The core principle is simple:

- Every video stays on the platform `MediaCodec` path and only hardware video decoders are eligible.
- Platform audio decoders run first; FFmpeg is used only when Android exposes no decoder for the audio format.
- Detect HDR10, HDR10+, and Dolby Vision from the actual video stream, not from titles or filenames.

## What this branch focuses on

- Use the current device's hardware video decoders for SDR, HDR10, and HDR10+ playback.
- Keep the complete Dolby Vision stream on devices that expose both hardware DV decoding and DV output capability.
- Use the HDR10/HLG base layer only when end-to-end native Dolby Vision is unavailable.
- Prefer platform decoding for Atmos, AC-3, E-AC-3/JOC, DTS, and TrueHD, then downmix all output to stereo PCM.
- Keep subtitles, fullscreen controls, and remote focus behavior consistent for TV use.
- Split the project into clearer deliverables for long-term maintenance.

## 0.2.7 Variants

| Variant | ABI | Target devices | Notes |
| --- | --- | --- | --- |
| `java32` | `armeabi-v7a` | Mainstream 32-bit Android TVs / smart screens | Primary TV build |
| `java64` | `arm64-v8a` | 64-bit Android phones / tablets / boxes | Current 64-bit build |
| `hisense` | `armeabi-v7a` | Hisense 32-bit TVs | Current dedicated Hisense build |

## What 0.2.7 focused on

- Fixed an intermittent non-zero Range response from local `6677/proxy/play` sources that returned a contaminated body consisting of a PNG signature followed by the video's EBML header. The data source now rejects the entire response and retries the original Range instead of feeding an image prefix to the Matroska extractor, which caused 0% buffering, black video, and playback timeouts.
- Kept the single foreground system-codec Range lane, the `8 MiB` first/foreground window, the bounded `16 MiB` window, and sustained large-file caching; malformed responses cannot regress normal startup, resume, or repeated scrubbing.
- Bounded source retries and active-buffer handling so malformed responses cannot loop forever while recoverable transient network errors still retry.
- All three variants retain device hardware video decoding, stereo PCM audio, HDR subtitle handling, Java64 touch/gesture/focus behavior, and the independent Hisense package/ABI. They install over `0.2.6`.

## 0.2.6 Variants

| Variant | ABI | Target devices | Notes |
| --- | --- | --- | --- |
| `java32` | `armeabi-v7a` | Mainstream 32-bit Android TVs / smart screens | Primary TV build |
| `java64` | `arm64-v8a` | 64-bit Android phones / tablets / boxes | Dedicated 64-bit build |
| `hisense` | `armeabi-v7a` | Hisense 32-bit TVs | Dedicated Hisense build |

## What 0.2.6 focused on

- The system-codec path uses one foreground Range loading lane with an `8 MiB` first/foreground window and a bounded `16 MiB` maximum, so real I/O begins immediately without a competing background reader.
- The confirmed eight-byte malformed Range response from `6677/proxy/play` is accepted narrowly. Normal short, empty, offset, and `416` responses remain strict retries and cannot become false cache windows.
- Resume, repeated forward/backward scrubbing, and data-source recreation retain verified length information, avoiding transient `416`, false 0% progress, and stale-read timeouts without reducing sustained large-file caching.
- Embedded and fullscreen prepare/rebuffer/seek states show real percentage and speed, then clear stale loading text, spinners, and seek icons once playback is stable.
- HDR bitmap subtitles render as opaque grey with a black outline; SDR remains white. Default-on subtitles, fullscreen control, Java64 touch/gesture/focus behavior, and the independent Hisense package/ABI remain intact.
- All builds use `0.2.6` (`versionCode 2060`) and the existing signing certificate for in-place updates from `0.2.5` and earlier.

## Highlights

### 1. Device hardware decoding

- Every video format stays on Android `MediaCodec`, with software-only video decoders excluded.
- Native HDR switching, MEMC, and vendor image processing remain in the device path.

### 2. Dolby Vision routing

- The app probes the stream and queries device capability before playback.
- A complete DV stream reaches the native decoder only when both hardware DV decoding and DV output are available.
- Other devices strip DV RPU/EL and restore HDR metadata only for the HDR10/HLG base-layer path; ordinary HEVC/HDR streams are untouched.

### 3. Audio capability routing

- Platform hardware audio decoders are ordered before platform software decoders and the FFmpeg extension.
- All decoded audio reaches one stereo PCM `AudioTrack`, preventing silence on optical/ARC outputs that cannot decode multichannel bitstreams.

### 4. Subtitles and audio

- Supports internal subtitles, source subtitles, external subtitles, and local subtitles.
- Automatically prefers Simplified Chinese / Traditional Chinese when available.
- Audio stays at full-scale stereo PCM and Android routes it to the active TV speaker, HDMI/ARC, or external digital output.

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
  -> query device video and audio decoder capability
  -> select a device hardware MediaCodec for video
  -> select a platform audio decoder, with FFmpeg only as fallback
  -> output stereo PCM and apply HDR, subtitle, and fullscreen policy
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

- `TVBox_v0.2.7_java32.apk`
- `TVBox_v0.2.7_java64.apk`
- `TVBox_v0.2.7_hisense32.apk`

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
- `0.2.5`: unify capability-aware routing across all variants: device hardware video decode only, complete native DV on capable devices, HDR base-layer fallback only elsewhere, platform-first audio decoding to stereo PCM, and synchronized buffering/seek/performance fixes.
- `0.2.6`: move the system-codec path to one foreground Range lane while retaining the large-file cache window, narrowly fix the eight-byte `6677/proxy/play` Range anomaly, and synchronize resume/scrub, buffering progress/speed, HDR bitmap subtitles, and loading-overlay cleanup across all variants.
- Next: keep closing playback, subtitle, HDR, and audio behavior from device logs.
