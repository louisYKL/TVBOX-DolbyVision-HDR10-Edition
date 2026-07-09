<div align="center">

# TV BOX Hisense 32-bit Edition

> A dedicated Hisense Android / Google TV branch synced to the `0.2.1` playback core.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.1"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.1-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## Current build

- Package name: `com.github.tvbox.osc.hisense`
- Version: `0.2.1`
- ABI: `armeabi-v7a`
- Output APK: `TVBox_v0.2.1_hisense32.apk`
- Minimum Android: 4.4 / API 19

## What was synced in 0.2.1

- Pulled in the 32-bit playback fixes for HLS sources such as Wen Cai where playback could fall into black-screen-with-audio, fake first-frame success, endless prepare, or false playback errors.
- Unified this branch on the `SurfaceView` and hardware-decode path only, removing the old software-decode fallback branch.
- Streams that require unsupported `H.264 High10` hardware decode now fail clearly with the hardware-decode-not-supported message.
- Reduced HLS prefetch, range-source cache, and runtime-log pressure to improve startup speed on low-end TVs.
- Added lightweight Apple TV / iOS-style transitions for page entry, back, and exit.

## Build

```powershell
.\build-local.ps1
```

All toolchains, SDKs, JDKs, and Gradle caches stay inside the repo-local `_runtime` directory.

## Compatibility notes

- This branch targets Hisense Android / Google TV models only.
- VIDAA models are not part of the Android APK route.
- The dedicated package name allows side-by-side installation with the main build `com.github.tvbox.osc`.
