# Local Build Environment

All Android build dependencies and caches for this project are kept under this repository's `_runtime` directory.

Do not re-download SDK, JDK, Gradle, or write build caches to `C:`.

Prepare the local runtime once with:

```powershell
.\setup-local-runtime.ps1
```

## Vendored paths

- JDK: `_runtime/jdk/temurin11/jdk-11.0.32.1+1`
- Android SDK: `_runtime/android-sdk` (platforms/android-34 and build-tools/34.0.0)
- Gradle cache/home: `_runtime/gradle-home`
- Android user home: `_runtime/android-user-home`
- HOME / USERPROFILE: `_runtime/home`
- TEMP / TMP: `_runtime/tmp`

## Build command

Use the repo-local wrapper script:

```powershell
.\build-local.ps1
```

To build one flavor only:

```powershell
.\build-local.ps1 -Tasks ":app:assembleJava64Debug"
```

## Output APK tasks

- 32-bit TV: `:app:assembleNormalDebug`
- 64-bit mobile/tablet: `:app:assembleJava64Debug`
- Hisense 32-bit: `:app:assembleHisenseDebug`

## Debug keystore

Android Gradle Plugin expects the debug keystore at `%USERPROFILE%\.android\debug.keystore`.
It may also read the Android preferences directory from `ANDROID_USER_HOME`.
`build-local.ps1` copies the vendored keystore from `_runtime/android-home/debug.keystore`
into both `_runtime/android-user-home/debug.keystore` and `_runtime/home/.android/debug.keystore`
so signing stays inside the repo.

Do not set `ANDROID_SDK_HOME` together with `ANDROID_USER_HOME`.
AGP treats that as an invalid conflicting configuration and aborts before evaluation.
