<div align="center">

# TV BOX 海信 32 位版

> 基于 `0.2.2` 播放核心同步的海信 Android / Google TV 独立分支。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.2"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.2-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## 当前版本

- 包名：`com.github.tvbox.osc.hisense`
- 版本号：`0.2.2`（`versionCode 2026`）
- ABI：`armeabi-v7a`
- 输出 APK：`TVBox_v0.2.2_hisense32.apk`
- 最低系统：Android 4.4 / API 19

## 0.2.2 同步内容

- 视频打开后立即预读取并积累足够缓存，达到目标后才出画面；准备、缓冲和拖动阶段显示真实百分比与网速。
- 初始历史进度和普通拖动统一使用单一 seek 状态机，连续快速拖动只执行最新目标，修复 seek 后卡住、旧进度回灌、假完成和误跳下一集。
- 全屏底部菜单新增默认开启的字幕开关，字幕在首帧/播放就绪后初始化，修复字幕缺失、双层字幕和轨道查询阻塞。
- 详情页新增滚动显示的当前视频文件名，并与历史集数、切集和换源保持一致。
- 音频直通会检查片源编码和 HDMI / ARC / eARC / 数字输出能力；不支持直通的格式由电视解码后继续输出到当前外置设备。
- 缩短并可取消播放前探测，限制预热内存缓存并隔离旧回调，降低低性能海信电视上的卡顿和掉帧。
- 保留 `com.github.tvbox.osc.hisense` 独立包名、Android 4.4 最低版本和 `armeabi-v7a` ABI，可与主版并存安装。

## 构建

```powershell
.\build-local.ps1
```

构建环境、SDK、JDK、Gradle 缓存全部来自仓内 `_runtime`，不依赖仓外目录。

## 兼容说明

- 本分支只针对海信 Android / Google TV 机型。
- VIDAA 机型不是 Android APK 路线，不在这个包的支持范围内。
- 保留独立包名，可与主版 `com.github.tvbox.osc` 并存安装。
