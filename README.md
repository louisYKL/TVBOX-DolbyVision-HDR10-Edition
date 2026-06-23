# TV BOX Dolby Vision / HDR10 Edition

<div align="center">

面向 Android TV、智慧屏、盒子与部分大屏 Android 设备的 TVBox 播放增强分支。  
重点不是堆功能按钮，而是把系统硬解、HDR 激发、Dolby Vision 路由、字幕、音频与客厅交互这条链路做扎实。

<p>
  <a href="https://github.com/louisYKL/TV-BOX-DolbyVision-HDR10-Edition/releases/tag/v0.1.4"><img alt="Release" src="https://img.shields.io/badge/release-v0.1.4-111111?style=for-the-badge"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV%20%7C%20Android-black?style=for-the-badge">
  <img alt="Variants" src="https://img.shields.io/badge/variants-java32%20%7C%20java64%20%7C%20hisense32-black?style=for-the-badge">
  <img alt="Playback" src="https://img.shields.io/badge/playback-HDR10%20%7C%20HDR10%2B%20%7C%20Dolby%20Vision-black?style=for-the-badge">
</p>

<p>
  <a href="https://github.com/louisYKL/TV-BOX-DolbyVision-HDR10-Edition/releases/tag/v0.1.4">下载 v0.1.4</a> ·
  <a href="CHANGELOG.md">更新记录</a> ·
  <a href="RELEASE_NOTES_v0.1.4.md">详细发布说明</a>
</p>

</div>

## 项目定位

这是一个围绕真实电视播放场景持续整理的 TVBox 分支。

很多分支在源接入层做得很多，但在真正影响观感的播放链路上仍然比较粗糙：HDR 判断依赖标题关键词、MKV 直接丢给系统播放器、字幕和音轨链路互相打架、电视遥控器交互不连贯、不同设备形态混在一个包里长期互相污染。

这个项目的目标正相反：

- 能稳定走系统播放器的内容，优先保留系统硬解、HDR 模式切换和厂商图像链。
- 系统链路不稳或容器兼容性差的内容，再进入兼容播放路径。
- HDR / HDR10+ / Dolby Vision 的决策尽量基于实际流信息，而不是标题猜测。
- 把 32 位电视主线、64 位 Android 线、海信专项线分开维护，降低回归和交叉污染。

## 核心能力

### 1. 系统播放器优先的播放哲学

- 优先尊重设备原生解码能力，而不是默认把所有内容都塞进同一条软件兼容链。
- 在电视端尽可能保留系统级硬解、色彩模式切换、硬件后处理和厂商针对大屏的优化。
- 对普通 MP4、TS、HDR10、HDR10+ 等常见内容优先走更接近原生体验的系统路径。

### 2. Dolby Vision / HDR 路由控制

- 以视频流探测、容器特征和设备能力为核心来选择播放链路。
- 对真正支持原生 Dolby Vision 的设备，尽量保留原生能力。
- 对不支持原生 Dolby Vision 但具备 HDR10 能力的设备，优先利用可用的 HDR10 基础层。
- 在更复杂的双层、兼容层或容器约束场景中，允许切到更稳的兼容路径，而不是硬顶系统链直至黑屏或灰屏。

### 3. MKV / WebM / 特殊片源兼容

- 为部分电视系统解码器不稳定的 MKV、WebM、HTTP HEVC、特殊封装片源保留兼容播放方案。
- 降低“理论支持、实际不能稳定起播”这类电视端常见问题。
- 让系统链和兼容链形成明确分工，而不是互相覆盖、互相打架。

### 4. 字幕链路

- 支持内嵌字幕、外挂字幕、片源字幕与本地字幕接入。
- 保留字幕探测、字幕装载与 TV 场景显示的独立实现空间。
- 目标不是只做到“字幕能显示”，而是让不同播放路线下的字幕行为尽可能一致。

### 5. 音频与轨道控制

- 保留音轨选择、音频直通与系统音量链协同的实现基础。
- 避免播放器内软件音量、系统音量、功放链路之间出现额外冲突。
- 为不同设备形态预留更细粒度的轨道和解码策略调整空间。

### 6. 大屏交互与控制层

- 以遥控器焦点、全屏控制层、返回路径、进度操作和菜单唤起为中心优化电视交互。
- 保持 TV 首页、点播、直播、历史、收藏、搜索、设置等主要功能区的一致操作逻辑。
- 兼顾大屏场景和部分触屏 Android 设备的使用差异。

### 7. 源接入与扩展能力

- 保留基于 `quickjs` 与 `pyramid` 的脚本与扩展能力。
- 支持 Java / JS / Python 相关能力在项目中的既有接入方式。
- 为接口源、解析逻辑、直播列表、订阅数据和辅助功能扩展保留空间。

## 三条产品线

当前仓库不是把三个版本硬揉成一个 flavor 工程，而是以一个 GitHub 仓统一发布三条彼此独立的代码线。

| 目录 | 对外版本线 | 目标设备 | 主要定位 |
| --- | --- | --- | --- |
| `TVBoxOS-main` | `java32` | 主流 32 位 Android TV / 智慧屏 / 电视盒子 | 当前电视主线，强调系统硬解、HDR 与电视交互 |
| `TVBoxOS-java64` | `java64` | 64 位 Android 设备 | 独立 64 位路线，单独维护播放与交互差异 |
| `TVBoxOS-hisense` | `hisense32` | 海信 Android / Google TV 32 位机型 | 海信专项线，保留独立包名和更保守的兼容策略 |

这种结构的价值在于：

- GitHub 层面只维护一个公开源码仓和一个 release 体系。
- 代码层面仍然保留三个独立目录，避免不同设备逻辑长期互相污染。
- 每条线都可以单独构建、单独演进、单独修复问题。

## 仓库结构

```text
.
├─ TVBoxOS-main/        32 位电视主线
├─ TVBoxOS-java64/      64 位 Android 独立线
├─ TVBoxOS-hisense/     海信 32 位独立线
├─ CHANGELOG.md         汇总更新记录
├─ RELEASE_NOTES_v0.1.4.md
└─ README.md
```

每个子目录都保留自己的：

- Gradle 工程
- `build-local.ps1`
- `README`
- `CHANGELOG`
- 平台专项代码与资源

## 播放架构概览

```text
内容源 / 订阅 / 本地数据
        ->
      媒体探测
        ->
判断容器 / 编码 / HDR 类型 / 设备能力
        ->
选择系统播放器或兼容播放链
        ->
挂接字幕 / 音轨 / 控制层 / 交互逻辑
        ->
输出到电视或 Android 大屏设备
```

这套思路的重点不是“永远只用一个播放器”，而是让播放器选择成为一个基于能力和兼容性的决策过程。

## 功能范围

项目当前围绕以下主要使用面展开：

- 首页聚合与站点入口
- 点播浏览与详情页
- 直播列表与频道切换
- 搜索、历史、收藏
- 外挂与在线字幕
- 音轨与播放控制
- 远程地址、订阅与配置导入
- 大屏设置项与播放相关策略开关

## Release 与下载

`v0.1.4` release 提供三端 APK 与配套发布资料：

| 文件 | 说明 |
| --- | --- |
| `TVBox_v0.1.4_java32.apk` | 32 位电视主线安装包 |
| `TVBox_v0.1.4_java64.apk` | 64 位 Android 安装包 |
| `TVBox_v0.1.4_hisense32.apk` | 海信 32 位专项安装包 |
| `TVBox_v0.1.4_RELEASE_NOTES.md` | 详细发布说明 |
| `TVBox_v0.1.4_SHA256SUMS.txt` | 产物校验信息 |

下载入口：

- [GitHub Releases / v0.1.4](https://github.com/louisYKL/TV-BOX-DolbyVision-HDR10-Edition/releases/tag/v0.1.4)

## 本地构建

三条线分别构建，不共用单一根工程任务。

### `TVBoxOS-main`

```powershell
cd TVBoxOS-main
.\build-local.ps1
```

### `TVBoxOS-java64`

```powershell
cd TVBoxOS-java64
.\build-local.ps1
```

### `TVBoxOS-hisense`

```powershell
cd TVBoxOS-hisense
.\build-local.ps1
```

如果需要更细粒度的环境说明，可分别查看：

- [`TVBoxOS-main/BUILD_ENV.md`](TVBoxOS-main/BUILD_ENV.md)
- [`TVBoxOS-java64/BUILD_ENV.md`](TVBoxOS-java64/BUILD_ENV.md)
- [`TVBoxOS-hisense/BUILD_ENV.md`](TVBoxOS-hisense/BUILD_ENV.md)

## 文档入口

- [总更新记录](CHANGELOG.md)
- [v0.1.4 详细发布说明](RELEASE_NOTES_v0.1.4.md)
- [TVBoxOS-main README](TVBoxOS-main/README.md)
- [TVBoxOS-java64 README](TVBoxOS-java64/README.md)
- [TVBoxOS-hisense README](TVBoxOS-hisense/README.md)

## 适合谁

- 想在 Android TV / 智慧屏上尽量保留系统硬解与 HDR 能力的人
- 需要更认真处理 Dolby Vision、HDR10、HDR10+ 与容器兼容关系的人
- 需要把 32 位电视、64 位 Android、海信专项线长期隔离维护的人
- 想在 TVBox 体系内继续做播放器链路与大屏交互工程化改造的人

## 说明

- 本项目不内置任何影视内容、直播源或订阅源。
- 请只接入你拥有合法使用权的内容源、字幕与配置数据。
- `Dolby Vision`、`HDR10`、`HDR10+`、`Android TV` 等名称归各自权利人所有。
