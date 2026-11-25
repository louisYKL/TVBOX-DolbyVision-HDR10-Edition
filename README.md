<div align="center">

# TV BOX-杜比视界/HDR10支持版

> 面向 Android TV、手机和平板的 TVBox 分支，重点维护设备硬解、HDR、字幕和有界流式缓存。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.3.6"><img alt="Release" src="https://img.shields.io/badge/release-v0.3.6-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV%20%2F%20Android-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
  <img alt="HDR" src="https://img.shields.io/badge/HDR-HDR10%20%7C%20HDR10%2B%20%7C%20Dolby%20Vision-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.3.6">下载 0.3.6</a> ·
  <a href="README.en.md">English</a> ·
  <a href="0.3.6/RELEASE_NOTES.md">本次更新说明</a>
</p>

</div>

## 下载

三端使用共同的播放器代码和缓存策略，按 ABI 分别构建。正式发布包沿用既有签名；覆盖安装同一变体可保留设置与播放记录。

| 安装包 | ABI | 适用设备 |
| --- | --- | --- |
| [TVBox_v0.3.6_java32.apk](https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/download/v0.3.6/TVBox_v0.3.6_java32.apk) | `armeabi-v7a` | 32 位 Android TV、智慧屏与盒子 |
| [TVBox_v0.3.6_java64.apk](https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/download/v0.3.6/TVBox_v0.3.6_java64.apk) | `arm64-v8a` | 64 位手机、平板与盒子 |
| [TVBox_v0.3.6_hisense32.apk](https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/download/v0.3.6/TVBox_v0.3.6_hisense32.apk) | `armeabi-v7a` | 海信 32 位电视 |

版本号为 `0.3.6`，`versionCode` 为 `30006`。发布目录同时提供校验文件；历史更新保留在 [CHANGELOG](CHANGELOG.md)。

## 0.3.6 更新重点

- 修复点播与 WebDAV 缓冲进度在真实缓存已经完成后仍停留在 `99%` 或 `100%` 不播放的问题。只有连续已提交数据满足启播门槛才完成进度，完成后立即清理加载提示。
- 修复从播放位置恢复、向前/向后 Seek 时的进度基准，短文件或尾部播放按当前位置之后的剩余数据计算，不再用固定缓存目标造成错误百分比。
- 修复 Seek overlay 的旧 UI 回调竞态；新的播放代次会使旧的 `100%`/速度提示失效，避免加载框覆盖已恢复的画面。

实现细节与验证范围见 [0.3.6 更新说明](0.3.6/RELEASE_NOTES.md)。

## 界面预览

<p align="center">
  <img src="1.jpg" alt="TVBOX preview 1" width="48%">
  <img src="2.webp" alt="TVBOX preview 2" width="48%">
</p>

## 播放、HDR 与音频

- 视频使用设备暴露的 Android `MediaCodec` 硬件解码器；不能由软件设置补齐设备不支持的解码能力。
- HDR 判断结合视频轨道、PQ/HLG、HDR 元数据和 Dolby Vision/HDR10+ 标记，避免仅凭标题、位深或广色域猜测。播放控制栏显示时，角标显示当前已识别的视频分辨率与 HDR/SDR 状态。
- 具备相应解码与显示能力的设备保留原生 Dolby Vision；其他设备仅在源视频具备实际兼容基础层时使用 HDR10/HLG/SDR 路径。应用请求相应输出模式，是否实际输出仍受系统、解码器、显示设备和格式配置限制。
- HDR 播放亮度由设备控制。无自定义样式的 HDR 文本字幕使用浅灰字与黑色描边；ASS/SSA、带样式的文本和 PGS/VobSub 位图保留源设计，SDR 保留原字幕样式。
- 音频优先使用系统解码器，必要时使用 FFmpeg 后备，保持现有双声道 PCM 输出和 Android 音频路由；本次内存优化不降低音频质量来掩盖缓冲问题。
- 触屏单击显示或隐藏播放栏，双击切换播放/暂停；播放栏按钮和电视遥控器也可操作播放。

## WebDAV 与外挂字幕

在设置中保存 WebDAV 地址、账号和密码，随后从首页“本地”入口浏览远程目录并播放。AList HTTP 地址可使用 `http://设备地址:5244/dav/`；HTTPS 域名或 IP 可使用 `https://服务器地址/dav/`（未写端口时默认 `443`）。

同目录同名字幕可自动加载；播放中的外挂字幕选择窗也可浏览其他本地或 WebDAV 文件夹。支持 SRT、ASS/SSA、VTT、TTML、SCC 和 STL，读取上限为 `8 MiB`。选择外挂字幕后切换到该字幕，避免内置字幕与外挂字幕同时渲染；选择记录可用于再次播放。

本地文件只显示 Android 允许应用读取的目录。若系统限制访问，请先在设置的存储权限入口授权；文件浏览不会绕过系统权限。

## 9978 网页输入

应用主进程启动时启动管理服务。使用同一局域网的浏览器打开 `http://设备IP:9978/api`；端口占用时以软件显示的实际地址为准。

1. 在网页中填写订阅源、直播源、WebDAV 地址、账号或密码中的一项。
2. 点击这一项旁边的确认。已打开的对应软件输入框会更新；尚未打开时暂存在内存，打开相应设置框后填入。
3. 在软件自己的设置弹窗中确认保存，才会保存并应用这次修改。网页确认和回车都不会代替软件确认、重启应用或自动播放。

五项互不覆盖。空值可以清空对应字段；尚未保存的网页输入不会跨应用重启保留。

## 进程与内存边界

独立全屏播放页和直播页使用 `:player` 进程。32 位电视的详情页保留内嵌预览，该播放器仍在详情页所属的主进程中，不能把它算作另一个独立堆。

Range 原始数据、解封装后的样本队列、字幕、图片与界面都会占用资源。同一播放器进程中的已缓存数据、元数据和在途块共享按堆大小确定的上限：32 位与低堆设备最高 `192 MiB`；高堆 64 位设备为 `192/288/384 MiB`。后台预取同时保留堆余量，低堆通常为 `96 MiB`、高堆为 `128 MiB`；补齐最小连续缓冲时按更紧的播放余量准入。若实际内存无法容纳所需窗口，播放页显示可重试的内存不足提示，保留页面供用户操作。增大视频文件体积不会按比例申请整个文件的内存。应用内部日志只记录错误，总量限制为 `512 KiB` 并自动轮转。

## 本地构建

构建工具、依赖和缓存使用仓库内的 `_runtime` 目录。准备本地 Android SDK、JDK 11 与既有签名文件后，在仓库根目录运行：

```powershell
.\build-local.ps1 -Tasks ':app:assembleNormalRelease'
.\build-local.ps1 -Tasks ':app:assembleJava64Release'
.\build-local.ps1 -Tasks ':app:assembleHisenseRelease'
```

脚本设置项目内的 SDK、Gradle、Android 用户目录和临时目录。正式发布前需核对三包版本、ABI、历史签名与 SHA-256；仓库基础 GitHub Actions 为调试构建，不代替该签名核验。

```text
app/        主应用、界面、HDR、字幕、WebDAV 与管理服务
player/     播放器抽象、原生播放与 Range 缓存
quickjs/    JS 引擎模块
pyramid/    Python 扩展模块
```

## 协作与说明

- [贡献指南](CONTRIBUTING.md) · [支持与反馈](SUPPORT.md) · [安全策略](SECURITY.md) · [更新记录](CHANGELOG.md)
- 本项目不内置影视内容、直播源或订阅源；请使用你有权访问的内容。
- `tvOS`、`iOS`、`Apple TV`、`Dolby Vision`、`HDR10`、`HDR10+` 等名称归各自权利人所有。
