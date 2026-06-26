<div align="center">

# TV Box Dolby Vision / HDR10 Edition

面向 Android TV、智慧屏、盒子和大屏 Android 设备的 TVBox 播放增强分支。  
重点不是堆功能按钮，而是把系统硬解、HDR、Dolby Vision、字幕、音轨、快进和遥控器交互这条链路做扎实。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.1.8"><img alt="Release" src="https://img.shields.io/badge/release-v0.1.8-111111?style=for-the-badge"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV%20%7C%20Android-black?style=for-the-badge">
  <img alt="Variants" src="https://img.shields.io/badge/variants-java32%20%7C%20java64%20%7C%20hisense32-black?style=for-the-badge">
  <img alt="Playback" src="https://img.shields.io/badge/playback-HDR10%20%7C%20HDR10%2B%20%7C%20Dolby%20Vision-black?style=for-the-badge">
</p>

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.1.8">下载 v0.1.8</a> ·
  <a href="CHANGELOG.md">更新记录</a> ·
  <a href="RELEASE_NOTES_v0.1.8.md">详细发布说明</a>
</p>

</div>

## 最新版本

`0.1.8` 重点修复了两类真实使用问题：

- 点播切源、快进过程里的卡顿、无限加载和卡死
- `java64` 直播黑屏有声音的问题

## 项目定位

这是一个围绕真实电视播放场景持续整理的 TVBox 分支。

很多分支在源接入层做得很多，但真正影响体验的还是播放链路本身：HDR 判断是否可靠、系统播放器是否能稳起播、字幕和音轨是否打架、快进和切源是否会卡死、不同设备形态是否互相污染。

这个项目的目标正相反：

- 能稳定走系统播放器的内容，优先保留系统硬解、HDR 和厂商图像链。
- 系统链路不稳或容器兼容性差的内容，再进入兼容播放路径。
- HDR / HDR10+ / Dolby Vision 的决策尽量基于实际流信息，而不是标题猜测。
- 把 32 位电视主线、64 位 Android 线、海信专项线分开维护，降低回归和交叉污染。

## 视觉预览

<p align="center">
  <img src="TVBoxOS-main/1.jpg" alt="TVBox preview 1" width="48%">
  <img src="TVBoxOS-main/2.webp" alt="TVBox preview 2" width="48%">
</p>

## 核心能力

### 系统播放器优先

- 优先尊重设备原生解码能力，而不是默认把所有内容都塞进同一条软件兼容链。
- 对普通 MP4、TS、HDR10、HDR10+ 等常见内容优先走更接近原生体验的系统路径。

### Dolby Vision / HDR 路由

- 以视频流探测、容器特征和设备能力来选择播放链路。
- 对支持原生 Dolby Vision 的设备尽量保留原生能力。
- 对不支持原生 DV 但具备 HDR10 能力的设备，优先利用可用的 HDR10 基础层。

### 兼容播放

- 为部分电视系统解码器不稳定的 MKV、WebM、HTTP HEVC、特殊封装片源保留兼容方案。
- 降低“理论支持、实际不能稳定起播”这类电视端常见问题。

### 字幕链路

- 支持内嵌字幕、外挂字幕、片源字幕与本地字幕接入。
- 让不同播放路线下的字幕行为尽可能一致。

### 音频与轨道控制

- 保留音轨选择、音频直通和系统音量链协同的实现基础。
- 避免播放器内软件音量、系统音量、功放链路之间出现额外冲突。

### 大屏交互

- 以遥控器焦点、全屏控制层、返回路径、进度操作和菜单唤起为中心优化电视交互。
- 保持首页、点播、直播、历史、收藏、搜索、设置等主要功能区的一致操作逻辑。

### 源接入与扩展

- 保留基于 `quickjs` 与 `pyramid` 的脚本与扩展能力。
- 支持 Java / JS / Python 相关能力在项目中的既有接入方式。

## 三条产品线

| 目录 | 对外版本线 | 目标设备 | 主要定位 |
| --- | --- | --- | --- |
| `TVBoxOS-main` | `java32` | 主流 32 位 Android TV / 智慧屏 / 电视盒子 | 当前电视主线 |
| `TVBoxOS-java64` | `java64` | 64 位 Android 设备 | 独立 64 位路线 |
| `TVBoxOS-hisense` | `hisense32` | 海信 Android / Google TV 32 位机型 | 海信专项线 |

这三个目录作为一个仓统一发布，但代码边界仍然保持独立。

## Release 交付

`v0.1.8` release 提供三端 APK 与配套发布资料：

| 文件 | 说明 |
| --- | --- |
| `TVBox_v0.1.8_java32.apk` | 32 位电视主线安装包 |
| `TVBox_v0.1.8_java64.apk` | 64 位 Android 安装包 |
| `TVBox_v0.1.8_hisense32.apk` | 海信 32 位专项安装包 |
| `TVBox_v0.1.8_RELEASE_NOTES.md` | 详细发布说明 |
| `TVBox_v0.1.8_SHA256SUMS.txt` | 产物校验信息 |

## 本地构建

三条线分别构建，不共用单一根工程任务。

### `TVBoxOS-main`

```powershell
cd TVBoxOS-main
.\build-local.ps1 -Tasks ":app:assembleNormalRelease"
```

### `TVBoxOS-java64`

```powershell
cd TVBoxOS-java64
.\build-local.ps1 -Tasks ":app:assembleJava64Release"
```

### `TVBoxOS-hisense`

```powershell
cd TVBoxOS-hisense
.\build-local.ps1 -Tasks ":app:assembleRelease"
```

## 功能范围

- 首页聚合与站点入口
- 点播浏览与详情页
- 直播列表与频道切换
- 搜索、历史、收藏
- 外挂与在线字幕
- 音轨与播放控制
- 远程地址、订阅与配置导入
- 大屏设置项与播放策略开关

## 说明

- 本项目不内置任何影视内容、直播源或订阅源。
- 请只接入你拥有合法使用权的内容源、字幕与配置数据。
- `Dolby Vision`、`HDR10`、`HDR10+`、`Android TV` 等名称归各自权利人所有。
