<div align="center">

# TV BOX 海信 32 位版

> 基于 `0.2.4` 播放核心同步的海信 Android / Google TV 独立分支。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.4"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.4-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## 当前版本

- 包名：`com.github.tvbox.osc.hisense`
- 版本号：`0.2.4`（`versionCode 2031`）
- ABI：`armeabi-v7a`
- 输出 APK：`TVBox_v0.2.4_hisense32.apk`
- 最低系统：Android 4.4 / API 19

## 0.2.4 同步内容

- 普通非 Dolby Vision 点播固定使用 Android `MediaPlayer` 系统硬解，不再静默切到兼容播放器；Dolby Vision 仍按原有专用链路播放。
- 恢复进度启动获得独立的范围 seek 等待时间；原生播放器持续缓冲可在 180 秒上限内延长外层安全网，避免首帧解码或连续快进时被误判超时。
- 保留 `com.github.tvbox.osc.hisense` 独立包名、Android 4.4 最低版本和 `armeabi-v7a` ABI，`0.2.4`（`versionCode 2031`）沿用原签名，可覆盖安装 `0.2.3` 及更早版本并与主版并存。

## 构建

```powershell
.\build-local.ps1
```

构建环境、SDK、JDK、Gradle 缓存全部来自仓内 `_runtime`，不依赖仓外目录。

## 兼容说明

- 本分支只针对海信 Android / Google TV 机型。
- VIDAA 机型不是 Android APK 路线，不在这个包的支持范围内。
- 保留独立包名，可与主版 `com.github.tvbox.osc` 并存安装。
