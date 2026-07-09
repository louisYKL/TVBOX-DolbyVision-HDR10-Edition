<div align="center">

# TV BOX 海信 32 位版

> 基于 `0.2.1` 播放核心同步的海信 Android / Google TV 独立分支。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.1"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.1-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## 当前版本

- 包名：`com.github.tvbox.osc.hisense`
- 版本号：`0.2.1`
- ABI：`armeabi-v7a`
- 输出 APK：`TVBox_v0.2.1_hisense32.apk`
- 最低系统：Android 4.4 / API 19

## 0.2.1 同步内容

- 同步 32 位主线对“文采”等 HLS 视频源黑屏有声音、假首帧、一直准备播放、误报播放出错的修复。
- 播放链路统一为 `SurfaceView` + 硬解优先，删除软解兜底路径，避免分支行为继续分叉。
- 对设备不支持硬解的 `H.264 High10` 片源，直接提示“此格式不支持硬件解码（H.264 High10）”。
- 降低 HLS 预取、Range 数据源缓存和运行日志压力，减少低性能电视上的空转、卡顿和播放页启动等待。
- 补齐页面进入、返回和退出的轻量级 Apple TV / iOS 风格动画。

## 构建

```powershell
.\build-local.ps1
```

构建环境、SDK、JDK、Gradle 缓存全部来自仓内 `_runtime`，不依赖仓外目录。

## 兼容说明

- 本分支只针对海信 Android / Google TV 机型。
- VIDAA 机型不是 Android APK 路线，不在这个包的支持范围内。
- 保留独立包名，可与主版 `com.github.tvbox.osc` 并存安装。
