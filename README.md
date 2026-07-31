<div align="center">

# TV BOX-杜比视界/HDR10支持版

> 为电视而做的 TVBox 分支，重点优化设备硬解、HDR 激发、杜比视界能力路由与大屏交互体验。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.6"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.6-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV%20%2F%20Android-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
  <img alt="HDR" src="https://img.shields.io/badge/HDR-HDR10%20%7C%20HDR10%2B%20%7C%20DV%20fallback-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.6">下载 0.2.6</a> ·
  <a href="README.en.md">English</a>
</p>

</div>

## 一句话介绍

这是一个面向真实客厅场景的 TVBox 分支：视频统一使用设备自己的 Android `MediaCodec` 硬件解码能力，音频优先由系统解码并输出双声道 PCM，同时保留 HDR、字幕、持续缓冲和遥控器交互。

## 下载版本

| 文件 | 适用设备 | 说明 |
| --- | --- | --- |
| `TVBox_v0.2.6_java32.apk` | 主流 32 位 Android TV / 智慧屏 | 当前主电视版本，可覆盖安装 0.2.5 及更早版本 |
| `TVBox_v0.2.6_java64.apk` | 64 位 Android 手机 / 平板 / 盒子 | 64 位独立版本，可覆盖安装 0.2.5 及更早版本 |
| `TVBox_v0.2.6_hisense32.apk` | 海信 32 位电视 | 海信电视专项版本，可覆盖安装 0.2.5 及更早版本 |

## 项目观感

<p align="center">
  <img src="1.jpg" alt="TVBOX preview 1" width="48%">
  <img src="2.webp" alt="TVBOX preview 2" width="48%">
</p>

## 项目定位

这不是一个只做“能播就行”的 TVBox 改版。

这个分支的目标，是把播放器、HDR 路由、杜比视界能力判断、字幕、音频输出、遥控器操作和电视端 UI 整理成一套更适合真实客厅场景的实现，让它在主流 Android TV、智慧屏和盒子上更稳定地工作。

我们优先尊重设备本身的能力：

- 所有视频统一交给设备系统 `MediaCodec`，仅选择设备暴露的硬件视频解码器。
- 音频优先使用系统 `MediaCodec` 解码器，系统不支持的编码才由 FFmpeg 后备解码。
- HDR / HDR10+ / Dolby Vision 的判断，以视频流探测为准，不靠标题猜测。

## 这个分支重点解决什么

- 让普通 SDR / HDR10 / HDR10+ 内容都使用当前设备自己的硬件视频解码器。
- 同时具备硬件 DV 解码器和 DV 显示能力的设备直接硬解原生杜比视界，不再剥离为 HDR10。
- 不具备端到端 DV 能力的设备才使用 HDR10 / HLG 基础层，并剥离 RPU/EL、补齐 HDR 元数据。
- 设备支持的 Dolby Atmos、AC-3、E-AC-3/JOC、DTS、TrueHD 等音频优先走系统解码，其他格式自动后备解码为双声道 PCM。
- 保持字幕、全屏控制、遥控器焦点和返回逻辑在电视场景下更连贯。
- 把项目拆成更明确的 32 位电视版、64 位 Android 版和海信 32 位版，便于后续长期维护。

## 0.2.6 当前版本包含

| 版本 | ABI | 面向设备 | 说明 |
| --- | --- | --- | --- |
| `java32` | `armeabi-v7a` | 主流 32 位 Android TV / 智慧屏 | 当前主电视版本 |
| `java64` | `arm64-v8a` | 64 位 Android 手机 / 平板 / 盒子 | 独立 64 位版本 |
| `hisense` | `armeabi-v7a` | 海信 32 位电视 | 独立海信专用版本 |

## 0.2.6 这次重点修了什么

- 系统硬解路径使用单一前台 Range 读取链：首包与前台窗口保持 `8 MiB`、最大窗口 `16 MiB`，不再与后台预读竞争，打开视频立即开始真实读取。
- 精确兼容已确认的 `6677/proxy/play` 八字节异常 Range 响应；普通短包、空包、错位响应和 `416` 继续严格重试，不会被缓存成错误窗口。
- 恢复进度、连续快进/回退和数据源重建会传递已确认文件长度，避免临时 `416`、0% 假进度或旧读取任务导致误超时；大文件持续缓存策略保持不降级。
- 小窗与全屏的准备、缓冲、拖动状态显示真实百分比和网速，首帧/缓冲结束后立即清理过期加载文字、旋转图标和 seek 提示。
- HDR 位图字幕统一为灰色字形和黑色描边，SDR 保持白色字形；保留字幕默认开启、全屏开关、字幕轨道恢复、Java64 触控/手势/焦点和海信独立包名/ABI。
- 三端统一为 `0.2.6`（`versionCode 2060`），沿用原签名，可覆盖安装 `0.2.5` 及更早版本。

## 核心特性

### 1. 设备硬件解码

- 所有视频格式统一走 Android `MediaCodec`，候选列表明确排除软件视频解码器。
- 保留电视原生 HDR 模式切换、MEMC 和厂商图像后处理能力。

### 2. Dolby Vision 兼容策略

- 播放前做视频流探测和设备能力查询，不靠文件名和标题猜。
- 设备同时具备硬件 DV 解码器与 DV 显示能力时，完整 DV 流直接进入原生硬解。
- 其他设备仅对 DV 流启用 HDR10/HLG 基础层与 RPU/EL 剥离，普通 HEVC/HDR 流不受影响。

### 3. 音频能力路由

- 系统音频硬件解码器优先于系统软件解码器和 FFmpeg 后备。
- 所有音频最终进入同一双声道 PCM `AudioTrack`，避免外接光纤/ARC 设备因多声道源码不受支持而无声。

### 4. 字幕与音频

- 支持内置字幕、片源字幕、外挂字幕与本地字幕。
- 自动优先简体中文 / 繁体中文字幕。
- 音频输出保持满幅双声道 PCM，由 Android 路由到当前电视扬声器、HDMI/ARC 或外接数字音频设备。

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
  -> 查询设备视频与音频解码能力
  -> 视频选择设备硬件 MediaCodec
  -> 音频选择系统解码器，必要时 FFmpeg 后备
  -> 输出双声道 PCM，并控制 HDR、字幕与全屏控制层
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

- `TVBox_v0.2.6_java32.apk`
- `TVBox_v0.2.6_java64.apk`
- `TVBox_v0.2.6_hisense32.apk`

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
- `0.2.5`：三端统一设备能力路由，视频只用设备硬解；原生 DV 设备保留完整 DV 流，非原生设备才使用 HDR 基础层；音频优先系统解码并统一输出双声道 PCM，同时同步实时缓冲进度、seek 和性能修复。
- `0.2.6`：系统硬解改为单一前台 Range 读取链，保持大文件缓存窗口并修复 `6677/proxy/play` 八字节异常 Range；恢复进度、连续拖动、缓冲进度/网速、HDR 位图字幕和加载层清理同步三端。
- 后续：继续按设备日志收敛播放器、字幕、HDR 与音频链路。
