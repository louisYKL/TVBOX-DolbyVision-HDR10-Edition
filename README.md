<div align="center">

# TV BOX 海信 32 位版

> 基于 `0.2.5` 播放核心同步的海信 Android / Google TV 独立分支。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.5"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.5-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## 当前版本

- 包名：`com.github.tvbox.osc.hisense`
- 版本号：`0.2.5`（`versionCode 2050`）
- ABI：`armeabi-v7a`
- 输出 APK：`TVBox_v0.2.5_hisense32.apk`
- 最低系统：Android 4.4 / API 19

## 0.2.5 同步内容

- 所有视频只选择海信设备暴露的硬件 `MediaCodec`，软件视频解码器不会进入候选；同时具备硬件 DV 解码和 DV 输出能力时保留原生 DV，否则使用 HDR 基础层。
- 系统音频解码优先，系统不支持的格式由 FFmpeg 后备，最终统一输出双声道 PCM；同步首播预缓冲、持续缓存、seek、缓冲百分比和实时网速修复。
- 能力探测和实际解码器选择统一排除软件 codec 与仅声明能力，FFmpeg 扩展缺失时仍保留海信设备自身的系统硬解链。
- 保留 `com.github.tvbox.osc.hisense`、Android 4.4 最低版本和 `armeabi-v7a` ABI；`0.2.5`（`versionCode 2050`）沿用原签名，可覆盖安装 `0.2.4` 及更早版本并与主版并存。

## 构建

```powershell
.\build-local.ps1
```

构建环境、SDK、JDK、Gradle 缓存全部来自仓内 `_runtime`，不依赖仓外目录。

## 兼容说明

- 本分支只针对海信 Android / Google TV 机型。
- VIDAA 机型不是 Android APK 路线，不在这个包的支持范围内。
- 保留独立包名，可与主版 `com.github.tvbox.osc` 并存安装。
