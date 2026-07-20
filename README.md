<div align="center">

# TV BOX 海信 32 位版

> 基于 `0.2.3` 播放核心同步的海信 Android / Google TV 独立分支。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.3"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.3-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## 当前版本

- 包名：`com.github.tvbox.osc.hisense`
- 版本号：`0.2.3`（`versionCode 2027`）
- ABI：`armeabi-v7a`
- 输出 APK：`TVBox_v0.2.3_hisense32.apk`
- 最低系统：Android 4.4 / API 19

## 0.2.3 同步内容

- 修复播放/快进/拖动时视频卡死不动最终报“播放超时”的根因：本地代理转发原始流的 OkHttp 客户端此前继承了无限读取超时，源站接受连接后中途停发数据时转发线程会永久阻塞、断点重连恢复永不触发，把原生播放器饿死。现给本地代理直连流、HLS/直播分片（`ts`）转发、外部 `go=stream` 透传和 m3u8 抓取统一加 20 秒“单次读取无数据”超时，只判定连接僵死不限制总时长，卡死连接会被放弃并自动重连。
- 修复缓冲期间被误判卡死：外层 45 秒安全网超时此前只靠缓冲百分比上涨刷新，而按已收字节计算的百分比在原生缓冲够转去解码时会冻结，导致健康播放被误杀；现把“原生持续缓冲中”当作存活信号刷新超时（上限 180 秒），真正的准备卡死仍会正常超时。
- 快进、拖动进度条、切集换源在慢速或不稳定源上更不易卡死；三端共享同一套播放与代理核心修复。
- 保留 `com.github.tvbox.osc.hisense` 独立包名、Android 4.4 最低版本和 `armeabi-v7a` ABI，`0.2.3`（`versionCode 2027`）沿用原签名，可覆盖安装 `0.2.2` 及更早测试版并与主版并存。

## 构建

```powershell
.\build-local.ps1
```

构建环境、SDK、JDK、Gradle 缓存全部来自仓内 `_runtime`，不依赖仓外目录。

## 兼容说明

- 本分支只针对海信 Android / Google TV 机型。
- VIDAA 机型不是 Android APK 路线，不在这个包的支持范围内。
- 保留独立包名，可与主版 `com.github.tvbox.osc` 并存安装。
