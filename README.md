<div align="center">

# TV BOX-杜比视界/HDR10支持版

> 为电视而做的 TVBox 分支，重点优化系统硬解、HDR 激发、杜比视界兼容链路与大屏交互体验。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.4"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.4-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV%20%2F%20Android-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
  <img alt="HDR" src="https://img.shields.io/badge/HDR-HDR10%20%7C%20HDR10%2B%20%7C%20DV%20fallback-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.4">下载 0.2.4</a> ·
  <a href="README.en.md">English</a>
</p>

</div>

## 一句话介绍

这是一个面向真实客厅场景的 TVBox 分支：能走系统硬解的内容尽量走系统硬解，系统链路不稳的 MKV / WebM / Dolby Vision 场景则切到内置兼容链路，让 HDR、字幕、音频直通和遥控器交互尽可能同时成立。

## 下载版本

| 文件 | 适用设备 | 说明 |
| --- | --- | --- |
| `TVBox_v0.2.4_java32.apk` | 主流 32 位 Android TV / 智慧屏 | 当前主电视版本，可覆盖安装 0.2.3 及更早测试版 |
| `TVBox_v0.2.4_java64.apk` | 64 位 Android 手机 / 平板 / 盒子 | 64 位独立版本，可覆盖安装 0.2.3 及更早测试版 |
| `TVBox_v0.2.4_hisense32.apk` | 海信 32 位电视 | 海信电视专项版本，可覆盖安装 0.2.3 |

## 项目观感

<p align="center">
  <img src="1.jpg" alt="TVBOX preview 1" width="48%">
  <img src="2.webp" alt="TVBOX preview 2" width="48%">
</p>

## 项目定位

这不是一个只做“能播就行”的 TVBox 改版。

这个分支的目标，是把播放器、HDR 路由、杜比视界兼容、字幕、音频直通、遥控器操作和电视端 UI 重新整理成一套更适合真实客厅场景的实现，让它在主流 Android TV、智慧屏和盒子上更稳定地工作。

我们优先尊重设备本身的能力：

- 系统播放器能稳定处理的内容，尽量交给系统播放器。
- 系统链路打不开、打不开稳、打不开 HDR 的内容，再交给内置兼容链路。
- HDR / HDR10+ / Dolby Vision 的判断，以视频流探测为准，不靠标题猜测。

## 这个分支重点解决什么

- 让普通 HDR10 / HDR10+ 内容优先走设备原生硬解链路。
- 为系统解码链不稳定的 MKV / WebM / 特定 Dolby Vision 场景补一条更可靠的兼容播放路径。
- 对不支持原生杜比视界的设备，优先使用 HDR10 基础层；必要时再进入兼容映射链路。
- 保持音频直通、字幕、全屏控制、遥控器焦点和返回逻辑在电视场景下更连贯。
- 把项目拆成更明确的 32 位电视版、64 位 Android 版和海信 32 位版，便于后续长期维护。

## 0.2.4 当前版本包含

| 版本 | ABI | 面向设备 | 说明 |
| --- | --- | --- | --- |
| `java32` | `armeabi-v7a` | 主流 32 位 Android TV / 智慧屏 | 当前主电视版本 |
| `java64` | `arm64-v8a` | 64 位 Android 手机 / 平板 / 盒子 | 独立 64 位版本 |
| `hisense` | `armeabi-v7a` | 海信 32 位电视 | 独立海信专用版本 |

## 0.2.4 这次重点修了什么

- 修复 Java64 直播“有声音、黑屏”：旧系统播放器释放前会先断开渲染 Surface，重试、换台也不会重复释放；系统播放器保留系统播放地址，兼容播放器才走兼容地址。
- 主 32 位和海信版的普通非 Dolby Vision 点播固定使用 Android `MediaPlayer` 系统硬解，不再静默切到兼容播放器；Dolby Vision 仍按原有专用链路播放。
- 恢复进度启动获得独立的范围 seek 等待时间；原生播放器持续缓冲可在 180 秒上限内延长外层安全网，避免首帧解码或连续快进时被误判超时。
- 三端保持 java64 触控/手势/焦点行为、字幕和音频链路，以及 Hisense 独立包名/ABI；版本统一为 `0.2.4`（`versionCode 2031`），沿用原签名，可覆盖安装 `0.2.3` 及更早版本。

## 核心特性

### 1. 系统播放器优先

- 普通 MP4 / TS / HDR10 / HDR10+ 内容优先走系统播放器。
- 尽量保留电视原生硬件解码、HDR 模式切换和厂商侧图像后处理能力。

### 2. Dolby Vision 兼容策略

- 播放前先做视频流探测，再决定播放器，不靠文件名和标题猜。
- 对不支持原生 DV 的设备：
- 若视频流存在 HDR10 基础层，优先走 HDR10 基础层。
- 若没有可直接利用的基础层，再切到内置兼容播放器做 HDR / SDR 回退。

### 3. MKV / WebM 兼容播放

- 针对部分电视系统解码器对 HTTP HEVC MKV 支持不稳定的问题，补充 MPV 兼容链路。
- 避免把本来能通过兼容链稳定播放的内容，硬塞给系统播放器后直接失败。

### 4. 字幕与音频

- 支持内置字幕、片源字幕、外挂字幕与本地字幕。
- 自动优先简体中文 / 繁体中文字幕。
- 音频直通跟随软件设置；软件内部音量保持满值，避免与系统 / 功放音量链冲突。

### 5. 电视端交互

- 以遥控器焦点、全屏控制层、进度操作和返回路径为中心做电视场景适配。
- UI 使用深色基底，并保留液态玻璃风格组件和 tvOS 风格视觉方向的基础能力。

## 为什么单独做这个分支

很多 TVBox 分支在“内容源”层面做得很多，但在电视播放链路本身上往往比较粗糙：

- HDR 判断依赖标题关键字。
- MKV / WebM 直接交给系统播放器，然后在部分电视上失败。
- 音频直通、字幕、全屏控制层和遥控器焦点互相打架。
- 32 位电视、64 位 Android、品牌机型差异都混在一个包里维护。

这个仓库的目标不是堆更多功能按钮，而是把播放链路、设备能力判断和大屏体验做扎实。

## 播放架构概览

```text
视频流探测
  -> 判断是否为 HDR10 / HDR10+ / Dolby Vision
  -> 判断容器与设备能力
  -> 选择系统播放器或兼容播放器
  -> 控制 HDR 请求、字幕策略、音频直通与全屏控制层
```

## 项目结构

```text
app/        Android 主应用
player/     播放器抽象与系统播放器链路
quickjs/    JS 引擎模块
pyramid/    Python 扩展模块
```

## 本地构建

项目已经按“构建依赖和缓存尽量留在项目目录内”的方式整理。

运行环境：

- Android SDK: `E:\tvbox\TVBoxOS-main\_runtime\android-sdk`
- JDK: `E:\tvbox\TVBoxOS-main\_runtime\jdk\temurin11\jdk-11.0.31+11`
- Gradle Home: `E:\tvbox\TVBoxOS-main\_runtime\gradle-home`

PowerShell：

```powershell
$env:JAVA_HOME='E:\tvbox\TVBoxOS-main\_runtime\jdk\temurin11\jdk-11.0.31+11'
$env:GRADLE_USER_HOME='E:\tvbox\TVBoxOS-main\_runtime\gradle-home'

.\gradlew.bat :app:assembleNormalDebug
.\gradlew.bat :app:assembleJava64Debug
.\gradlew.bat :app:assembleHisenseDebug
```

## GitHub Actions

仓库内置基础 Android 构建工作流，可直接产出：

- `TVBox_v0.2.4_java32.apk`
- `TVBox_v0.2.4_java64.apk`
- `TVBox_v0.2.4_hisense32.apk`

## 社区协作

- [贡献指南](CONTRIBUTING.md)
- [支持与反馈](SUPPORT.md)
- [安全策略](SECURITY.md)
- [更新记录](CHANGELOG.md)

## 适合谁

- 想要在 Android TV / 智慧屏上尽量保留系统硬解与 HDR 激发的人。
- 需要单独维护 32 位电视版、64 位 Android 版和品牌专项版的人。
- 想基于 TVBox 做更认真播放器链路改造，而不是只改壳或只换源的人。

## 说明

- 本项目不内置任何影视内容、直播源或订阅源。
- 请只接入你有合法使用权的内容源、字幕和订阅数据。
- `tvOS`、`iOS`、`Apple TV`、`Dolby Vision`、`HDR10`、`HDR10+` 等名称归各自权利人所有。

## Roadmap

- `0.1`：完成 32 位电视版、64 位 Android 版、海信 32 位版的版本收口。
- `0.1.1`：继续修正 HDR / DV 探测、播放器路由、播放进度保存与切源状态隔离。
- `0.1.3`：收紧 64 位系统播放器触控/渲染路径，并统一整理三套 Android 发布包。
- `0.1.9.1`：三端源码和 APK 作为同一个 GitHub Release 发布。
- `0.2.0`：修复 32 位系统播放器黑屏有声音、播放失败、播放出错风险，并保持可覆盖安装 `0.1.9.1`。
- `0.2.1`：修复“文采”HLS 源黑屏有声音/假首帧/误报错，统一 `SurfaceView` 硬解链路，对不支持硬解的 `H.264 High10` 直接提示不支持。
- `0.2.2`：重构预缓冲和 seek 状态机，恢复稳定音频/字幕/进度保存，增加缓冲百分比、全屏字幕开关和详情页文件名，并同步三端。
- `0.2.3`：修复本地代理转发流因无限读取超时导致播放/快进/拖动卡死最终报“播放超时”的根因，给直连流、HLS 分片、外部透传和 m3u8 抓取统一加有限读取超时；并把原生持续缓冲当作存活信号，避免缓冲期间误杀健康播放。
- `0.2.4`：修复 Java64 直播黑屏有声和释放时 Surface 生命周期冲突；主 32 位和海信版普通非 Dolby Vision 点播固定走系统硬解；恢复进度和连续拖动时为健康的原生缓冲保留受限等待时间，避免误超时。
- 后续：继续按设备日志收敛播放器、字幕、HDR 与音频链路。
