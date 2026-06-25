# TVBox 0.1.5 详细更新日志

## 发布概览

`0.1.5` 继续统一发布当前三条 Android 线：

- `java32`：32 位电视主线
- `java64`：64 位 Android 独立线
- `hisense32`：海信 32 位独立线

本次 release 只做版本同步和三包重发，不改播放功能。

## 本次 release 资产

- `TVBox_v0.1.5_java32.apk`
- `TVBox_v0.1.5_java64.apk`
- `TVBox_v0.1.5_hisense32.apk`
- `TVBox_v0.1.5_RELEASE_NOTES.md`
- `TVBox_v0.1.5_SHA256SUMS.txt`

## 0.1.5 主要变更

### 1. 仓库与交付方式

- 将 `java32`、`java64`、`hisense32` 统一提升到 `0.1.5`。
- 保持三条线仍然独立构建、独立打包、独立发布。
- release 资产继续提供 APK、更新日志和 SHA256 校验清单。

### 2. 构建结果

- `TVBoxOS-main` 产出 `java32` 和 `java64` 发布包。
- `TVBoxOS-hisense` 产出 `hisense32` 发布包。
- 三条线的功能逻辑保持不变。

## 构建记录

- `TVBoxOS-main\build-local.ps1 -Tasks ":app:assembleNormalRelease",":app:assembleJava64Release",":app:assembleHisenseRelease"`
- `TVBoxOS-hisense\build-local.ps1 -Tasks ":app:assembleRelease"`

## 产物对应关系

- `TVBoxOS-main` -> `TVBox_v0.1.5_java32.apk`
- `TVBoxOS-main` -> `TVBox_v0.1.5_java64.apk`
- `TVBoxOS-hisense` -> `TVBox_v0.1.5_hisense32.apk`
