<div align="center">

# TV BOX Hisense 32-bit Edition

> A dedicated Hisense Android / Google TV branch synced to the `0.2.5` playback core.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.5"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.5-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## Current build

- Package name: `com.github.tvbox.osc.hisense`
- Version: `0.2.5` (`versionCode 2050`)
- ABI: `armeabi-v7a`
- Output APK: `TVBox_v0.2.5_hisense32.apk`
- Minimum Android: 4.4 / API 19

## What 0.2.5 fixed

- Every video selects a hardware `MediaCodec` exposed by the Hisense device; native DV is kept only when both hardware DV decode and DV output are available, otherwise the HDR base layer is used.
- Platform audio decoding runs first, FFmpeg is only a fallback, and output is always stereo PCM; startup prebuffer, sustained cache, seek, buffering percentage, and live speed fixes are synchronized.
- Capability probing and decoder selection consistently exclude software codecs and declaration-only support; the Hisense platform codec route still works when the FFmpeg extension is unavailable.
- The dedicated `com.github.tvbox.osc.hisense` package, Android 4.4 minimum, and `armeabi-v7a` ABI remain unchanged. Version `0.2.5` (`versionCode 2050`) uses the existing signing certificate, installs over earlier Hisense builds, and remains side-by-side installable with the main package.

## Build

```powershell
.\build-local.ps1
```

All toolchains, SDKs, JDKs, and Gradle caches stay inside the repo-local `_runtime` directory.

## Compatibility notes

- This branch targets Hisense Android / Google TV models only.
- VIDAA models are not part of the Android APK route.
- The dedicated package name allows side-by-side installation with the main build `com.github.tvbox.osc`.
