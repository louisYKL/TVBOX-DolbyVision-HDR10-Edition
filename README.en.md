<div align="center">

# TV BOX Hisense 32-bit Edition

> A dedicated Hisense Android / Google TV branch synced to the `0.2.3` playback core.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.3"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.3-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## Current build

- Package name: `com.github.tvbox.osc.hisense`
- Version: `0.2.3` (`versionCode 2027`)
- ABI: `armeabi-v7a`
- Output APK: `TVBox_v0.2.3_hisense32.apk`
- Minimum Android: 4.4 / API 19

## What 0.2.3 fixed

- Fixes the root cause of playback/seek/scrub freezing and the eventual "播放超时": the local proxy's stream-forwarding OkHttp client inherited an infinite read timeout, so when an origin CDN accepted the connection then stalled mid-body the forwarding thread blocked forever, the existing reconnect recovery never ran, and the native player was starved. Local-proxy streaming, HLS/live segment (`ts`) forwarding, foreign `go=stream` passthrough, and m3u8 playlist fetches now share a 20s per-read inactivity timeout that abandons and reconnects a dead connection without capping total download time.
- Fixes healthy playback being killed while buffering: the outer 45s safety-net timeout only refreshed on rising buffer percentage, but direct HDR streaming derives that percentage from received bytes, which freezes once the native player buffers enough and switches to decoding the first frame. Sustained native buffering now counts as a liveness signal (capped at 180s); genuine prepare hangs still time out.
- All three variants share the same playback and proxy core, so fast-forward, timeline scrubbing, and episode/source switching are far less likely to freeze on slow or unstable sources.
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
