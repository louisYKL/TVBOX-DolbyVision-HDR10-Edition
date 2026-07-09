# Changelog

## 0.2.0

- Fixed the shared system-player playback path used by the Hisense 32-bit build:
  - HDR/DV and local-proxy MKV playback no longer forces the old safe-pcm `GAME/SPEECH` audio attributes.
  - Direct network playback now preserves external request headers such as `User-Agent`, `Referer`, `Origin`, and `Cookie`.
  - Surface/Display detach failures during teardown or rebuild are treated as non-fatal.
- Updated the standalone Hisense package to version `0.2.0`.
- Verified the generated APK is still `armeabi-v7a` only.

## 0.1.4-hisense

- Split the Hisense build out of the shared TVBox workspace into a standalone repository.
- Removed the multi-flavor build entrypoints from this repo and kept only the Hisense 32-bit Android TV package.
- Switched the package identity to `com.github.tvbox.osc.hisense`.
- Fixed standalone build breakage caused by flavor-only `BuildConfig.FLAVOR` checks by collapsing this repo to an explicit 32-bit TV target.
- Removed the unused `pyramid` Python module from the Hisense build graph so the APK can build cleanly with repo-local tooling only.
- Forced conservative native library packaging for vendor TV compatibility:
  - `android:extractNativeLibs="true"`
  - `jniLibs.useLegacyPackaging=true`
- Verified the resulting APK only contains `armeabi-v7a` native libraries.
