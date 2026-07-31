<div align="center">

# TV BOX Hisense 32-bit Edition

> A dedicated Hisense Android / Google TV branch synced to the `0.2.6` playback core.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.6"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.6-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## Current build

- Package name: `com.github.tvbox.osc.hisense`
- Version: `0.2.6` (`versionCode 2060`)
- ABI: `armeabi-v7a`
- Output APK: `TVBox_v0.2.6_hisense32.apk`
- Minimum Android: 4.4 / API 19

## What 0.2.6 fixed

- The system-codec path now uses one foreground Range loading lane with an `8 MiB` first/foreground window and a bounded `16 MiB` maximum, so real I/O starts without a competing background reader.
- The confirmed eight-byte malformed `6677/proxy/play` Range response is accepted narrowly; normal short, empty, offset, and `416` responses remain strict retries, preventing false windows, false 0% progress, and playback timeouts.
- Resume, repeated forward/backward scrubbing, buffering percentage, and transfer speed are synchronized to Hisense while retaining sustained large-file caching; stale loading text, spinners, and seek icons clear after stable playback.
- HDR bitmap subtitles use opaque grey glyphs with a black outline, while SDR remains white. The dedicated `com.github.tvbox.osc.hisense` package, Android 4.4 minimum, and `armeabi-v7a` ABI remain unchanged. Version `0.2.6` (`versionCode 2060`) uses the existing signing certificate, installs over `0.2.5` and earlier builds, and remains side-by-side installable with the main package.

## Build

```powershell
.\build-local.ps1
```

All toolchains, SDKs, JDKs, and Gradle caches stay inside the repo-local `_runtime` directory.

## Compatibility notes

- This branch targets Hisense Android / Google TV models only.
- VIDAA models are not part of the Android APK route.
- The dedicated package name allows side-by-side installation with the main build `com.github.tvbox.osc`.
