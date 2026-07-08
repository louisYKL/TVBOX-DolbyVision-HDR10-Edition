<div align="center">

# 影视仓 海信版

> 面向海信 Android / Google TV 机型的独立 TVBox 分支。

</div>

## 项目定位

这个仓库是从当前稳定的 32 位电视基线拆出来的海信独立仓，不再和 `java32` / `java64` 共用同一个构建入口。

目标不是单纯换包名，而是把海信电视这条安装和运行兼容链单独维护：

- 独立仓库
- 独立包名
- 独立版本号
- 独立 APK 输出
- 默认打包 `armeabi-v7a` + `arm64-v8a`

## 为什么要有海信版

海信电视并不是单一平台：

- 一部分是 Android TV / Google TV，可以安装 APK。
- 一部分是 VIDAA，这条线本身不是 Android APK 生态。

所以“海信版 APK”只对海信 Android / Google TV 机型成立，不能拿同一个 APK 去覆盖 VIDAA 机型。

同时，不同海信电视机型对可安装 ABI 的支持并不一致。当前默认 APK 会同时携带 `armeabi-v7a` 和 `arm64-v8a`，避免因为单一 32 位包在部分机型上触发安装失败。

## 当前版本

- 包名：`com.github.tvbox.osc.hisense`
- 版本号：`0.1.4-hisense`
- ABI：`armeabi-v7a` + `arm64-v8a`
- 最低系统：Android 4.4 / API 19

## 构建输出

默认输出：

- `TVBoxHisense_debug-universal.apk`

构建命令：

```powershell
.\build-local.ps1
```

## 兼容策略

- 保留 Android TV / Leanback 启动器入口和 TV banner。
- APK 同时打包 `armeabi-v7a` 和 `arm64-v8a`。
- `extractNativeLibs=true`。
- `jniLibs.useLegacyPackaging=true`。
- 不依赖仓外 SDK / JDK / Gradle / 缓存目录。

## 目录说明

- `app/`：主应用
- `player/`：播放器链路
- `quickjs/`：JS 引擎

## 注意

- 本仓只针对海信 Android / Google TV 机型。
- VIDAA 机型不是 APK 路线，不在这个包的支持范围内。
