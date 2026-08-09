<div align="center">

# TV BOX Hisense 32-bit Edition

> A dedicated Hisense Android / Google TV branch synced to the `0.2.8` playback core.

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.8"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.8-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## Current build

- Package name: `com.github.tvbox.osc.hisense`
- Version: `0.2.8` (`versionCode 2080`)
- ABI: `armeabi-v7a`
- Output APK: `TVBox_v0.2.8_hisense32.apk`
- Minimum Android: 4.4 / API 19

## What 0.2.8 fixed

- Routes controlled in-app `do=m3u8` / `proxyM3u8` sources through `go=live&type=m3u8`, keeping HLS playlists and media segments on the controlled local path instead of treating local HLS URLs as ordinary direct streams.
- Preserves the raw escaping level of nested signed URLs, so values such as `%2B` and `%25` are not invalidated by a second decode pass.
- Fetches HLS segments through independent HTTP/1.1 connections with bounded system-DNS, configured-DNS, then system-DNS recovery. Child playlists without a `.m3u8` suffix are recognized as playlists rather than media segments.
- Terminal HTTP failures end the current controlled HLS request instead of making the system player repeatedly rebuild the same playback URL; existing large-file Range, saved-progress, `416`, and repeated-scrub recovery boundaries remain unchanged.
- The independent `com.github.tvbox.osc.hisense` package, `armeabi-v7a` ABI, device hardware decode, stereo PCM, HDR subtitles, and existing signing certificate remain intact. Version `0.2.8` (`versionCode 2080`) installs over `0.2.7` and earlier releases and remains side-by-side installable with the main package.

## What 0.2.7 fixed

- Fixed an intermittent non-zero Range response from local `6677/proxy/play` sources that returned a contaminated body consisting of a PNG signature followed by the video's EBML header. The data source now rejects the entire response and retries the original Range instead of feeding an image prefix to the Matroska extractor, avoiding 0% buffering, black video, and playback timeouts.
- Kept the single foreground system-codec Range lane, the `8 MiB` first/foreground window, the bounded `16 MiB` window, and sustained large-file caching; malformed responses cannot regress normal startup, resume, or repeated scrubbing.
- Bounded source retries and active-buffer handling so malformed responses cannot loop forever while recoverable transient network errors still retry.
- The dedicated Hisense package, `armeabi-v7a` ABI, subtitle/audio/HDR routing, and existing signing certificate remain unchanged. This build installs over `0.2.6` and earlier releases.

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
