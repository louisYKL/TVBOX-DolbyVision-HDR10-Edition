<div align="center">

# TV BOX 海信 32 位版

> 基于 `0.2.6` 播放核心同步的海信 Android / Google TV 独立分支。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.6"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.6-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## 当前版本

- 包名：`com.github.tvbox.osc.hisense`
- 版本号：`0.2.6`（`versionCode 2060`）
- ABI：`armeabi-v7a`
- 输出 APK：`TVBox_v0.2.6_hisense32.apk`
- 最低系统：Android 4.4 / API 19

## 0.2.6 同步内容

- 系统硬解改为单一前台 Range 读取链，首包和前台窗口保持 `8 MiB`、最大窗口 `16 MiB`，不与后台 Range 预读竞争，打开视频立即开始真实读取。
- 精确兼容已确认的 `6677/proxy/play` 八字节异常 Range；普通短包、空包、偏移错误和 `416` 仍严格重试，避免错误窗口、0% 假进度和播放超时。
- 恢复进度、连续快进/回退、缓冲百分比和实时网速同步到海信端，保持大文件持续缓存；首帧/缓冲结束后清理过期加载文字、旋转图标和 seek 提示。
- HDR 位图字幕使用灰色字形和黑色描边，SDR 保持白色字形；保留 `com.github.tvbox.osc.hisense`、Android 4.4 最低版本和 `armeabi-v7a` ABI。`0.2.6`（`versionCode 2060`）沿用原签名，可覆盖安装 `0.2.5` 及更早版本并与主版并存。

## 构建

```powershell
.\build-local.ps1
```

构建环境、SDK、JDK、Gradle 缓存全部来自仓内 `_runtime`，不依赖仓外目录。

## 兼容说明

- 本分支只针对海信 Android / Google TV 机型。
- VIDAA 机型不是 Android APK 路线，不在这个包的支持范围内。
- 保留独立包名，可与主版 `com.github.tvbox.osc` 并存安装。
