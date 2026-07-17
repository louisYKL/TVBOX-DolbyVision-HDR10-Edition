<div align="center">

# TV BOX Hisense 32-bit Edition

> A dedicated Hisense Android / Google TV branch synced to the `0.2.2` playback core.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.2"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.2-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## Current build

- Package name: `com.github.tvbox.osc.hisense`
- Version: `0.2.2` (`versionCode 2026`)
- ABI: `armeabi-v7a`
- Output APK: `TVBox_v0.2.2_hisense32.apk`
- Minimum Android: 4.4 / API 19

## What was synced in 0.2.2

- Playback reads and prebuffers immediately, reveals video after the target buffer is ready, and reports real percentage plus network speed during prepare, rebuffer, and seek states.
- Initial resume and normal scrubbing share one seek coordinator; rapid repeated input keeps only the latest target and avoids stale progress, false completion, episode skips, or post-seek stalls.
- Fullscreen controls include a default-on subtitle toggle, while subtitle discovery waits for the first frame/playback-ready state to avoid missing, duplicated, or blocking tracks.
- The detail page displays the active video filename as a continuous marquee and keeps it aligned with history restoration, episode changes, and source changes.
- Passthrough validates stream codecs against HDMI / ARC / eARC / digital-output capabilities and decodes unsupported formats without rerouting audio away from the active external device.
- Short cancellable probes, bounded warmup caches, and generation-isolated callbacks reduce stalls and dropped frames on lower-end Hisense hardware.
- The dedicated `com.github.tvbox.osc.hisense` package, Android 4.4 minimum, and `armeabi-v7a` ABI remain unchanged for side-by-side installation.

## Build

```powershell
.\build-local.ps1
```

All toolchains, SDKs, JDKs, and Gradle caches stay inside the repo-local `_runtime` directory.

## Compatibility notes

- This branch targets Hisense Android / Google TV models only.
- VIDAA models are not part of the Android APK route.
- The dedicated package name allows side-by-side installation with the main build `com.github.tvbox.osc`.
