# TVBox 0.1.8 详细发布说明

## 发布概览

`0.1.8` 继续统一发布三条 Android 线：

- `java32`：32 位电视主线
- `java64`：64 位 Android 独立线
- `hisense32`：海信 32 位独立线

本次 release 重点收敛两个方向：

- 点播切源、快进卡顿、偶发卡死和进度误保存
- `java64` 直播黑屏有声音的问题保持修复状态，不回退

## 本次 release 资产

- `TVBox_v0.1.8_java32.apk`
- `TVBox_v0.1.8_java64.apk`
- `TVBox_v0.1.8_hisense32.apk`
- `TVBox_v0.1.8_RELEASE_NOTES.md`
- `TVBox_v0.1.8_SHA256SUMS.txt`

## 0.1.7 -> 0.1.8

### 1. 点播快进与切源稳定性

- 收紧了快进中的状态保护，避免多次快进后进入死等加载态。
- 修复了切源后偶发播放失败、超时和卡住的问题。
- 同步处理了进度保存边界，避免同一播放页里的进度被写成结束时间或串到其他视频。

### 2. 三端同步

- `java32`、`java64`、`hisense32` 继续按独立仓和独立包名构建。
- `hisense32` 复用 32 位链路的修复，但保持自己的专项目录和安装边界。
- `java64` 保持之前修复过的直播黑屏问题，并吃到同一轮播放稳定性修复。

## 构建记录

- `TVBoxOS-main\\build-local.ps1 -Tasks ":app:assembleNormalRelease"`
- `TVBoxOS-java64\\build-local.ps1 -Tasks ":app:assembleJava64Release"`
- `TVBoxOS-hisense\\build-local.ps1 -Tasks ":app:assembleRelease"`

## 产物对应关系

- `TVBoxOS-main` -> `TVBox_v0.1.8_java32.apk`
- `TVBoxOS-java64` -> `TVBox_v0.1.8_java64.apk`
- `TVBoxOS-hisense` -> `TVBox_v0.1.8_hisense32.apk`
