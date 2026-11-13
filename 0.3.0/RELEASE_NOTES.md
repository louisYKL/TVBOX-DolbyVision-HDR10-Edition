# TVBox 0.3.0

`versionName 0.3.0` · `versionCode 30000`

## 中文

本次更新针对电视日志中确认的 Java 堆内存耗尽，集中调整缓存预算、重新缓冲状态和播放资源生命周期，并更新 9978 远程输入。三端继续共用核心代码，保持现有签名、硬件解码和音频输出策略。

### 内存与缓存

- 网络 Range 块保持 `24 MiB`；普通点播/WebDAV 正常启播目标为当前播放位置之后连续的 `96 MiB`。文件剩余内容不足时以剩余有效数据为准。
- 原始 Range 缓存的配置上限为 `192 MiB`。该额度需要同时覆盖进程内缓存和在途块，并为解封装样本、字幕、图片、界面及其他任务留出空间；不能把缓存额度当作播放器的总内存占用。
- 当前进程的实际堆余量参与缓存预算。共享同一进程的旧数据源也占用预算，不能在切源时各自申请完整额度；取消或关闭后尚未退出的读取任务仍计入预算。
- 普通扩展预取预留 `96 MiB` Java 堆空间；补齐最小 `96 MiB` 连续缓冲和必要前台读取时至少保留 `48 MiB` 给其余对象。最小启播目标保持不变；若实际预算无法容纳且没有可等待归还的在途额度，播放页显示可重试的内存不足提示，保留当前页面，不自动换流或无限等待。暂停和播放时均继续执行预算允许的后台预取。
- 播放中的后台读取继续缓存视频结束方向的数据；已消费的块可释放，尚未播放的有效块保留。尾部索引和格式探测读取与播放位置之后的连续缓存分别统计。
- 首播、恢复和 Seek 按实际连续可用字节计算缓冲百分比。真正耗尽后重新建立一次缓冲阶段，达到本阶段所需数据并恢复实际输出后结束；等待时保留有效块，避免反复重建播放源或清空缓存。
- 直播仍使用独立的流式缓冲方式，不套用点播的大字节启播门槛。

### 生命周期与界面性能

- 退出播放或切源时立即终止旧会话、取消解析和字幕任务，并使已排队回调失效；终止后的页面不会因恢复前台或延迟回调重新启动旧视频。
- 保存历史/进度失败不再阻止播放器和数据源清理。刷新首页数据源时清理旧页面堆栈，避免旧详情预览随页面返回而留存。
- 播放、准备和暂停期间不反复绘制整页背景来制作模糊；静态玻璃背景按需保留，低内存设备降低背景采样成本。
- 视频探测直接进行有界字节扫描，减少二进制大字符串、重复大小写转换和短期内存峰值。取消探测时保留中断状态，避免在已取消任务后继续启动后备探测。
- 海报内存缓存按设备预算限制在 `4–16 MiB`，低内存通知触发清理。内部日志只记录错误，总量限制为 `512 KiB` 并轮转。

### 9978 网页输入

应用主进程启动管理服务，网页入口为 `http://设备IP:9978/api`；如端口冲突，以软件显示的实际地址为准。

| 网页字段 | 对应软件输入框 |
| --- | --- |
| 订阅源 | 点播订阅地址 |
| 直播源 | 直播地址 |
| WebDAV 地址 | WebDAV 服务器地址 |
| WebDAV 账号 | WebDAV 用户名 |
| WebDAV 密码 | WebDAV 密码 |

每项有自己的确认按钮，仅处理自己的字段，不覆盖其他项。确认后，已打开的对应输入框直接更新；未打开则暂存在内存，打开相应弹窗时填入。网页返回的“已填入”与“待填入”状态区分实际填框结果，不把接收请求误报为保存成功。

网页输入不会保存配置、自动点击软件确定、关闭软件弹窗、重启应用或自动播放。用户在软件弹窗内确认后才保存并启用；回车不会自动提交。空值可清空对应字段，未保存的待填入内容不会跨应用重启保留。密码不在响应和日志中回显。

### 字幕、HDR 与音频

- 外挂字幕在播放界面内浏览本地和 WebDAV 目录，可进入子目录、返回上级，不要求与视频同名；本地访问遵守 Android 存储权限。支持 SRT、ASS/SSA、VTT、TTML、SCC 和 STL，单次读取上限 `8 MiB`。
- 手动选择外挂字幕后切换到该字幕，避免与内置轨道重复显示；所选字幕可在再次播放时恢复。
- 无源样式的 HDR 纯文字字幕使用浅灰字和黑色描边。ASS/SSA、带标记或样式的文本保留源设计；PGS/VobSub 位图保留原像素，不做额外重着色或整幅复制。SDR 使用原字幕样式。
- 保留 HDR10、HDR10+、HLG 和 Dolby Vision 的视频轨道/元数据探测及设备能力路由。控制栏内的分辨率和 HDR/SDR 标识来自已识别的视频信息，不能代替显示设备输出状态的测量。
- HDR 亮度仍交由设备控制。音频继续使用系统解码优先、必要时 FFmpeg 后备的既有策略与双声道 PCM 输出，不降低音频质量来掩盖缓存问题。

### 进程、兼容性与发布包

独立全屏播放页和直播页运行在 `:player` 进程。32 位电视保留详情页内嵌预览，其播放器仍在主进程；这部分界面、源解析和缓存共享同一个堆。本次以实际所属进程约束资源，不宣称所有播放组件都已拆成独立进程。

更大缓存可应对短时网络波动，但不能把低于视频码率的持续下载速度变成流畅播放保证。HDR 实际输出取决于设备固件、硬件解码器、显示设备和具体格式配置；识别到 HDR 不代表任意设备都能输出该格式。

| 文件 | ABI |
| --- | --- |
| `TVBox_v0.3.0_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.0_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.0_hisense32.apk` | `armeabi-v7a` |

正式发布包沿用既有签名，支持同变体覆盖更新。`SHA256SUMS.txt` 用于核对下载文件；正式签名与校验应以本次发布的实际 APK 为准。

### 验证范围

统一 Gradle 测试完成：`player` 模块 `164` 项、`app` 模块 `177` 项，共 `341` 项，失败和错误均为 `0`。管理网页的 `18` 项检查通过。三端 Release 构建完成；已核对版本 0.3.0 / 30000、单一目标 ABI、非调试运行标记和既有签名证书，并为发布包生成 SHA-256 校验文件。

本轮不包含 `200 GB` 级影片的实机长时间连续播放验证，也未覆盖所有 Android 版本、厂商固件和 HDR 输出组合。自动化逻辑回归与三端构建只能验证各自范围内的行为；持续播放表现还需结合具体设备和片源测试。

## English

This release addresses Java heap exhaustion confirmed in TV logs through resource budgeting, rebuffer coordination, and prompt session teardown. It also updates remote input on port 9978. All variants retain shared core code, the existing signing certificate, hardware decoding, and audio output policy.

### Memory and buffering

- Network Range chunks remain `24 MiB`. Normal VOD/WebDAV startup targets `96 MiB` of contiguous data ahead of the playback position; shorter remaining content uses the available remainder.
- The configured raw Range cache ceiling is `192 MiB`. Its budget covers process-local cached and in-flight blocks while leaving room for demuxed samples, subtitles, images, UI, and other tasks. This is not a limit on total player memory.
- Effective budgeting considers current heap headroom. Old data sources sharing the process also consume the budget instead of each independently reserving the full amount during a source transition; cancelled or closed readers remain charged until they exit.
- Normal expansion prefetch leaves `96 MiB` of Java heap headroom. Completing the minimum `96 MiB` contiguous runway and essential foreground reads leaves at least `48 MiB` for other objects. Pressure does not silently lower the startup target. When actual memory cannot support that minimum and no in-flight ownership can be returned, keep a retryable memory error on the playback page instead of switching sources or waiting indefinitely. Budget-permitted background prefetch continues both while paused and while playing.
- Background reads continue toward the end of the video. Consumed blocks can be released while valid unplayed data is retained. Index and format probes do not count as contiguous data ahead of playback.
- Startup, resume, and seek progress use actual contiguous usable bytes. A genuine underrun arms one new rebuffer episode; it ends after the required runway is available and real output resumes. Existing valid cache is retained instead of repeatedly rebuilding the source or discarding downloaded data.
- Live retains its independent streaming buffers and does not inherit the VOD byte gate.

### Lifecycle and UI performance

- Exiting or changing sources immediately terminates the old session, cancels parser/subtitle work, and invalidates queued callbacks. Returning to the foreground or receiving a delayed callback cannot restart a terminated video.
- Progress/history-save failures no longer skip player and data-source cleanup. Home-source refresh clears old page stacks to avoid retaining detail previews.
- Preparing, playing, and paused sessions avoid repeated full-page rendering for blur. Static glass backdrops are retained as needed, with coarser sampling on low-memory devices.
- Stream probing scans bounded byte arrays instead of constructing large binary strings and repeated lowercase copies. Cancellation preserves interruption rather than starting another fallback probe.
- Device budgets bound the poster memory cache to `4–16 MiB`, and low-memory notifications trigger trimming. Internal logs record errors only and rotate within `512 KiB` total.

### Port 9978 remote input

The main process starts the management server. Open `http://device-IP:9978/api`, using the address shown in the app if its default port is occupied.

Subscription URL, live URL, WebDAV URL, username, and password each have an independent confirmation button and map to their matching application input. An open input updates immediately; otherwise a draft waits in memory for the matching settings dialog. The response distinguishes an actual filled input from a pending fill instead of reporting request receipt as a saved configuration.

Browser input does not save configuration, press the app's confirmation button, dismiss the app dialog, restart the app, or start playback. Confirm inside the app to save and activate changes. Enter does not submit. Empty values can clear a field; unsaved drafts do not survive restart. Responses and logs do not echo passwords.

### Subtitles, HDR, and audio

- Browse local or WebDAV directories in the external-subtitle dialog, including child folders and their parent. Manual selections need not match the video filename. Local access follows Android permissions. Supported formats are SRT, ASS/SSA, VTT, TTML, SCC, and STL, with an `8 MiB` read limit.
- Selecting an external subtitle switches to it rather than overlaying it with an internal track. The choice can be restored on later playback.
- Unstyled HDR text uses light-gray glyphs and a black outline. ASS/SSA, marked-up text, and styled cues retain their source presentation. PGS/VobSub bitmap pixels are kept without recoloring or full-image copies. SDR keeps the original subtitle style.
- Retain HDR10/HDR10+/HLG/Dolby Vision track and metadata detection and capability-aware output routing. Resolution and HDR/SDR badges describe identified video information, not a measurement of the display's actual output state.
- HDR brightness remains device-controlled. Platform-first audio decoding, FFmpeg fallback where needed, and stereo PCM output are preserved; audio quality is not reduced to hide cache problems.

### Processes, compatibility, and packages

Standalone fullscreen playback and live activities run in `:player`. The 32-bit TV detail preview remains embedded in the main process, sharing its heap with UI, source parsing, and caching. Resource budgets follow the actual host process; this release does not claim every playback component is independently isolated.

A larger cache absorbs temporary network fluctuations but cannot guarantee uninterrupted playback when sustained transfer is slower than the video bitrate. HDR output depends on firmware, hardware decoder, display, and format profile; identifying HDR does not make every device capable of outputting it.

The release contains `TVBox_v0.3.0_java32.apk` (`armeabi-v7a`), `TVBox_v0.3.0_java64.apk` (`arm64-v8a`), and `TVBox_v0.3.0_hisense32.apk` (`armeabi-v7a`). Packages retain the existing certificate for in-place updates of the same variant. Use `SHA256SUMS.txt` to verify downloads; signing and checksum verification must use the actual release APKs.

### Verification scope

The unified Gradle test run completed with `164` player tests and `177` app tests: `341` total, with `0` failures and `0` errors. All `18` management-page checks passed. All three Release builds completed. The packages were verified for version 0.3.0 / 30000, the single intended ABI, a non-debuggable runtime, and the existing signing certificate. SHA-256 checksums were generated for the release packages.

This iteration does not include a long-duration device playback test of a `200 GB` video or every Android version, vendor firmware, and HDR output combination. Automated logic regressions and three-variant builds verify their respective scopes; sustained playback still requires testing the actual device and source.
