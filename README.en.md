<div align="center">

# TV BOX Hisense 32-bit Edition

> A dedicated Hisense Android / Google TV branch synced to the `0.2.4` playback core.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.4"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.4-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## Current build

- Package name: `com.github.tvbox.osc.hisense`
- Version: `0.2.4` (`versionCode 2031`)
- ABI: `armeabi-v7a`
- Output APK: `TVBox_v0.2.4_hisense32.apk`
- Minimum Android: 4.4 / API 19

## What 0.2.4 fixed

- Ordinary non-Dolby-Vision VOD now stays on Android `MediaPlayer` hardware decoding rather than silently falling back to the compatibility player. Dolby Vision keeps its dedicated native or compatibility route.
- Saved-progress startup has its own range-seek allowance. Sustained native buffering can defer the outer safety net only up to 180 seconds, preventing healthy first-frame decoding or repeated seeks from being timed out.
- The package remains `com.github.tvbox.osc.hisense`, Android 4.4 / API 19 remains the minimum, and ABI remains `armeabi-v7a`. Version `0.2.4` (`versionCode 2031`) uses the existing signing certificate and installs in place over `0.2.3` and earlier builds while remaining separate from the main package.
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
