# Local Build Environment

All Android build dependencies for the Hisense project are vendored inside this repo under `_runtime`.

Do not download SDK, JDK, Gradle, or write caches to `C:`.

## Vendored paths

- JDK: `_runtime/jdk/temurin11/jdk-11.0.31+11`
- Android SDK: `_runtime/android-sdk`
- Gradle home: `_runtime/gradle-home`
- Android user home: `_runtime/android-user-home`
- HOME / USERPROFILE: `_runtime/home`
- TEMP / TMP: `_runtime/tmp`

## Build

```powershell
.\build-local.ps1
```

Or explicitly:

```powershell
.\build-local.ps1 -Tasks ":app:assembleDebug"
```

## Output

- `app/build/outputs/apk/debug/TVBoxHisense_debug-universal.apk`

## Notes

- This repo is a standalone Hisense Android TV project.
- The default APK now carries both `armeabi-v7a` and `arm64-v8a` so it can install on more Hisense TV variants.
- It is not the shared multi-flavor build from the main TVBox workspace anymore.
- Keep all toolchain state inside this repo.
