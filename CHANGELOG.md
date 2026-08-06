# Changelog

## 0.2.7

### 中文

- 修复本地 `6677/proxy/play` 在恢复进度的非零 Range 请求中偶发返回“PNG 文件头 + EBML 视频头”的污染正文。数据源现在识别并丢弃整段错误响应后重试原始 Range，避免图片头进入 Matroska 解码器后造成缓冲 0%、黑屏或播放超时。
- 保持系统硬解单一前台 Range 读取链、`8 MiB` 首包/前台窗口、最大 `16 MiB` 窗口和大文件持续缓存；异常 Range 不会破坏正常首播、恢复进度或连续拖动。
- 收紧源重试次数和活动缓冲状态边界，避免异常响应无限重试，同时保留临时网络失败的自动恢复。
- Java32、Java64 和 Hisense 三端同步本次播放核心与回归测试，保留设备硬解、立体声 PCM、HDR 字幕、Java64 触控/手势/焦点链、海信独立包名/ABI 和原签名证书。三端统一为 `0.2.7`、`versionCode 2070`，可覆盖安装 `0.2.6`。

### English

- Fixed an intermittent non-zero Range response from local `6677/proxy/play` sources that returned a contaminated body consisting of a PNG signature followed by the video's EBML header. The data source now rejects the entire response and retries the original Range, preventing an image prefix from reaching the Matroska extractor and causing 0% buffering, black video, or playback timeouts.
- Preserved the single foreground system-codec Range lane, the `8 MiB` first/foreground window, the bounded `16 MiB` window, and sustained large-file caching; malformed responses cannot regress normal startup, resume, or repeated scrubbing.
- Bounded source retries and active-buffer handling so malformed responses cannot loop forever while recoverable transient network errors still retry.
- Synchronized the playback core and regression tests across Java32, Java64, and Hisense while retaining hardware video decoding, stereo PCM audio, HDR subtitles, the Java64 touch/gesture/focus chain, the independent Hisense package/ABI, and the existing signing certificate. All variants are `0.2.7` with `versionCode 2070`, installable over `0.2.6`.

## 0.2.6

### 中文

- 修复本地 `6677/proxy/play` 源的非标准 Range 响应：只有“请求起点 `+8`、请求终点 `-8`、正文连续且少 8 字节”这一已确认形态会兼容处理；普通短包、空包、错误偏移和 `416` 仍严格重试，不再把错误数据伪装成完整缓存窗口。
- 系统硬解播放器改为单一前台 Range 读取链，首包和前台窗口保持 `8 MiB`、最大窗口 `16 MiB`；系统解码路径不再与后台 Range 预读竞争，也不再被启动预读任务长时间阻塞，打开视频后立即开始真实读取。
- 保留大文件持续缓存、恢复进度和重复拖动保护；跨数据源重建传递已确认的文件长度，避免带进度视频因临时 `416` 被误判为加载失败或播放超时。
- 准备、缓冲和 seek 期间显示真实缓冲百分比与网速；首帧、缓冲结束或详情页小窗稳定后清理过期的加载文字、旋转图标和快进/回退提示。
- 修复位图字幕在 HDR 输出下透明或颜色异常的问题：HDR 使用不透明灰色字形和黑色描边，SDR 保持白色字形；保留字幕默认开启、全屏开关和轨道恢复能力。
- 播放核心、回归测试和版本配置同步到 Java64 与 Hisense；保留 Java64 触控/手势/焦点链、Hisense 独立包名 `com.github.tvbox.osc.hisense` 与 `armeabi-v7a` ABI。三端统一为 `0.2.6`、`versionCode 2060`，沿用原签名证书。

### English

- Fixed the confirmed non-standard Range response from local `6677/proxy/play` sources. Only the exact contiguous-body shape with request start `+8`, request end `-8`, and an eight-byte short body is accepted; ordinary short, empty, offset, and `416` responses still retry strictly instead of becoming false cache windows.
- The system-codec path now uses one foreground Range loading lane with an `8 MiB` first/foreground window and a bounded `16 MiB` maximum. It no longer competes with a background Range reader or waits behind a startup-prebuffer gate, so opening a video starts real I/O immediately.
- Preserved sustained large-file caching, resume positions, and repeated-scrub protection. A verified content length is carried across data-source recreation so a temporary `416` on a saved-position read cannot become a false load failure or timeout.
- Buffering percentage and transfer speed remain visible during prepare, rebuffer, and seek; stale loading text, spinners, and seek icons are cleared after the first frame, buffering end, or stable embedded preview.
- Normalized bitmap subtitles for HDR output: opaque grey glyphs with a black outline on HDR, white glyphs on SDR. Subtitle default-on behavior, fullscreen toggle, and track recovery remain intact.
- Synchronized the playback core, regression tests, and version configuration to Java64 and Hisense while preserving Java64 touch/gesture/focus behavior, the Hisense package `com.github.tvbox.osc.hisense`, and `armeabi-v7a`. All variants are `0.2.6` with `versionCode 2060` and the existing signing certificate.

## 0.2.5

### 中文

- 三端同步统一播放器核心：ExoPlayer 负责时钟、解封装和持续缓存，视频只从设备声明的硬件 `MediaCodec` 解码器中选择，不再走软件视频解码或 MPV 兼容播放器。
- 新增设备能力驱动的杜比视界路由。设备同时具备 `video/dolby-vision` 硬件解码器和 Dolby Vision 输出能力时保留完整杜比视界流并直接硬解；不具备端到端能力时，才对 DV 流剥离 RPU/EL、补齐 HDR10/HLG 元数据并解码基础层。
- 音频渲染器固定为系统 `MediaCodec` 优先、FFmpeg 后备。设备支持的 Dolby Atmos、AC-3、E-AC-3/JOC、DTS、TrueHD 等格式优先使用设备硬解；所有解码结果经过降混后以双声道 PCM 输出，避免光纤/ARC 音箱因多声道源码无声。
- 收紧硬件能力判定：DV、HEVC Main10、AVC High10 和音频候选不再把 Google/C2 软件解码器或仅有配置声明的能力误判为硬解；FFmpeg 扩展不可用时也不会阻断设备自身可用的 `MediaCodec` 播放链。
- 将 Java32 已验证的首播预缓冲、持续前向缓存、恢复进度前置 seek、无限次拖动保护、假播放完成保护、缓冲百分比和实时网速显示同步到 Java64 与 Hisense。
- 保留 Java64 的触控、手势、单击和焦点链，以及 Hisense 独立包名 `com.github.tvbox.osc.hisense` 和 `armeabi-v7a` ABI；内部日志轮转上限继续保持 1 MB。
- 版本统一为 `0.2.5`，`versionCode 2050`，沿用原签名证书，可覆盖安装 `0.2.4` 及更早版本。

### English

- Synchronized one playback core across all variants: ExoPlayer owns the clock, demuxer, and sustained buffering; video selects only device-declared hardware `MediaCodec` decoders, with no software video or MPV compatibility route.
- Added capability-driven Dolby Vision routing. Devices exposing both a `video/dolby-vision` hardware decoder and Dolby Vision output keep the complete DV stream for native hardware decode; devices without end-to-end capability strip DV RPU/EL and restore HDR10/HLG metadata only for the base-layer path.
- Audio renderer order is platform `MediaCodec` first and FFmpeg second. Device-supported Dolby Atmos, AC-3, E-AC-3/JOC, DTS, and TrueHD use platform hardware decode when available; every decoded result is downmixed to stereo PCM so optical/ARC speakers do not receive unsupported multichannel bitstreams.
- Tightened hardware capability detection so DV, HEVC Main10, AVC High10, and audio routing do not mistake Google/C2 software codecs or declaration-only flags for hardware support. A missing FFmpeg extension no longer blocks streams the device can decode with `MediaCodec`.
- Synchronized the verified Java32 startup prebuffer, sustained forward cache, pre-prepare resume seek, repeated-scrub protection, false-completion guard, buffering percentage, and live network speed display to Java64 and Hisense.
- Preserved Java64 touch, gesture, single-tap, and focus behavior, plus the independent Hisense package `com.github.tvbox.osc.hisense` and `armeabi-v7a` ABI. Internal log rotation remains capped at 1 MB.
- Unified all builds under `0.2.5` with `versionCode 2050` and the existing signing certificate for in-place updates from `0.2.4` and earlier.

## 0.2.4

### 中文

- 修复 64 位直播重试/切台时的渲染面释放竞争：先解除旧 `Surface` 与系统播放器的绑定，再移除渲染视图并释放播放器；重试链不会重复释放同一个播放器。系统播放器继续使用系统供流地址，兼容播放器才使用兼容供流地址，修复“有声音但黑屏”。
- 普通非杜比视界点播在 32 位主版和海信版强制使用 Android 系统 `MediaPlayer` 硬件解码；不再被旧兼容播放器配置或失败回退覆盖。真实杜比视界仍保留独立的原生/兼容路由。
- 恢复历史进度的首个 range seek 获得独立启动窗口；系统播放器处于活跃原生缓冲时，外层超时仅在 180 秒上限内延期，不会在解码首帧或连续快进后错误释放健康播放器。真正准备挂死、原生错误和超过上限仍会正常报错恢复。
- 三端继续保留字幕、音频输出、Java64 触控/手势/焦点链，以及海信独立包名和 32 位 ABI。

### English

- Fixed the Java64 live retry/channel-switch surface-release race: detach the old `Surface` from the system player before removing the render view and releasing the player; the retry path no longer releases the same player twice. System playback keeps the system URL route and only the compatibility player uses the compatibility URL, fixing audio-only black video.
- Ordinary non-Dolby-Vision VOD is pinned to Android `MediaPlayer` hardware decoding on the main 32-bit and Hisense variants, so stale compatibility-player settings and failure fallback cannot override it. Real Dolby Vision keeps its dedicated native/compatibility route.
- Saved-progress startup has its own initial range-seek allowance. While the native player is actively buffering, the outer watchdog defers only within a 180-second ceiling, so first-frame decoding and repeated seeks are not released prematurely. Genuine prepare hangs, native errors, and the ceiling still fail normally.
- All variants retain subtitle and audio behavior, the Java64 touch/gesture/focus chain, and the independent Hisense package and 32-bit ABI.

## 0.2.3

### 中文

- 修复本地代理流客户端继承 `ItvClient` 无限读超时的根因：源站接受连接后中途停发数据时，代理的 `read()` 会永久阻塞，已有的重开/重试恢复逻辑永远不会触发，原生播放器被饿死直到应用强制杀掉播放（"播放超时"/卡死）。现为本地代理流设置 20 秒每次读取的空闲超时（限制的是"连接卡死"，不是总下载时长），卡死连接被及时放弃并自动重开恢复。
- 将同一无限读超时根因扩展修复到另外三条同样在 NanoHTTPD 线程上直接给原生播放器供数的通路：HLS/直播 `ts` 分片转发、非本地 `go=stream` 透传、以及 m3u8 播放列表与分片预取抓取。这些通路此前全部继承无限读超时，源站中途卡住会导致 HLS 源快进/拖动后无限卡死、播放无法开始或直播列表刷新后卡住。现统一走带有限空闲读超时的流式客户端（保留连接复用），静默源被放弃而不是永久挂起。
- 外层缓冲超时把"原生播放器持续缓冲中"当作存活信号刷新（上限 180 秒），避免在代理"检测卡死-放弃-重开"恢复窗口内误杀健康的播放器；仅对缓冲态生效，真正的准备卡死仍会正常超时报错。
- 修复 64 位直播"有声音无画面"：系统播放器解码出音频却始终没有视频输出时，会上报 `MEDIA_INFO_VIDEO_NOT_PLAYING`（805）。此前 `0.2.3` 一度把 805 改为"交给首帧看门狗判定"，但当原生缓冲一直为真时看门狗会无限期推迟，导致直播永久停在有声音无画面。本版恢复到已验证的处理：805 直接触发 `onError`，驱动直播界面的自动换源/重播恢复（`shouldTreatAsVideoStartupFailure` 已排除启播瞬时 805 和纯音频轨，只有真正"有音频无视频"才会走到这里），不再永久黑屏。
- 修复点播全屏"画面比例"按钮点击无效：此前被写死为 `SCREEN_SCALE_DEFAULT`，永远停在默认比例、无法切换。现在按 默认→16:9→4:3→填充→原始→裁剪 循环切换并保存。
- 修复点播全屏切换到杜比视界/HDR 兼容（MPV）播放器时"切换音轨"始终显示"没有音轨"：全屏音轨逻辑此前只识别系统播放器，现在与小窗一致地同时支持系统播放器和 MPV 兼容播放器，多音轨影片可正常切换。
- 三端（java32、java64、Hisense）共用同一套修复。版本统一为 `0.2.3`（`versionCode 2030`），沿用原签名证书，可覆盖安装 `0.2.2` 及更早的 `0.2.1.x`。

### English

- Fixed the root cause where the local-proxy stream client inherited `ItvClient`'s infinite read timeout: when an origin accepts the connection then stalls mid-body, the proxy's `read()` blocks forever, the existing reopen/retry recovery never runs, and the native player is starved until the app force-kills playback (freeze / "播放超时"). The local-proxy stream now uses a 20s per-read inactivity timeout (bounding stall, not total download), so a wedged connection is abandoned and reopened.
- Extended the same infinite-read-timeout fix to three more paths that also feed the native player directly from a NanoHTTPD worker: HLS/live `ts` segment serving, foreign `go=stream` passthrough, and m3u8 playlist + segment-prefetch fetches. These previously all inherited the infinite read timeout, so a stalled origin caused indefinite freezes on HLS sources after seek/fast-forward, playback that never started, or hangs after a live-playlist refresh. They now share a bounded-read streaming client (keeping connection reuse) so a silent origin is abandoned instead of hanging.
- The outer buffer-stall safety-net now treats sustained native buffering as a liveness signal (bounded by a 180s ceiling), so it no longer kills a healthy player during the proxy's detect-stall/abandon/reopen recovery window; it applies only to the buffering state, and a genuine prepare hang still times out.
- Fixed 64-bit live "audio but no video": when the system player decodes audio but never produces video output, it reports `MEDIA_INFO_VIDEO_NOT_PLAYING` (805). An earlier `0.2.3` attempt changed 805 to "defer to the first-frame watchdog", but while native buffering stayed true the watchdog deferred indefinitely and the channel stayed audio-only with a black screen forever. This restores the proven handling: 805 fails into `onError`, driving the live UI's automatic source-switch / replay recovery (`shouldTreatAsVideoStartupFailure` already excludes transient startup 805 and audio-only track lists, so only genuine audio-without-video reaches here) instead of a permanent black screen.
- Fixed the VOD fullscreen "画面比例" (aspect-ratio) button, which was hardcoded to `SCREEN_SCALE_DEFAULT` and could never reach any other mode. It now cycles 默认 → 16:9 → 4:3 → 填充 → 原始 → 裁剪.
- Fixed the fullscreen audio-track switch for HDR / Dolby Vision playback routed to the compatibility player: `PlayActivity.selectMyAudioTrack` only handled the system player, so multi-audio files on the compat player showed "没有音轨". It now resolves and switches tracks for the compatibility player too, matching the embedded-preview behavior.
- Shipped across all three variants (java32, java64, Hisense). Unified under `0.2.3` (`versionCode 2030`) with the existing signing certificate for in-place updates from `0.2.2` and earlier `0.2.1.x`.

## 0.2.2

### 中文

- 同版本最终热修复修正首帧后停住的根因：当本地代理在缓存边界暂时返回空的 `206` Range 响应或响应体提前结束时，数据源会在可取消的时限内重开同一范围，不再向系统解码器误报 EOF；恢复已经验证的前台读取与后台预读交接，避免每次缺块都取消预读。
- 32 位端把单次预读块限制为 4 MB，同时保留 24 MB 启播目标、32 MB 前向缓存目标和 40 MB 总缓存上限，降低大对象 GC、首帧卡死和长时间界面无响应风险；真实首帧出现后，旧代际准备/缓冲任务不能重新显示详情页加载层。
- 收敛字幕轨道合并、连续 seek、播放器释放和进度落库顺序，修复空样式首页崩溃，并停止写入运行日志与磁盘日志文件；三端同步这些修复且不改变 java64 触控链和 Hisense 包名/ABI 边界。
- 重构系统播放器的启动与预读取流程：打开视频后立即开始读取，使用连续 Range 窗口和后台预取持续维持大缓存；预读取不再作为阻塞播放的门，也不会因自身超时进入错误状态，播放时机只由系统播放器真实的 prepared、缓冲和首帧事件决定。
- 初始历史进度恢复与普通拖动统一交给单一 `SeekCoordinator`：快速连续拖动时新目标立即覆盖旧目标，不再排队播放中间位置；prepared 阶段提交恢复进度后可以立即启动，暂停期间拖动仍保持暂停，避免多层 seek 状态机互相等待。
- 修复 seek 后的进度保存和假播放完成：seek 进行中优先保存最新目标位置，忽略旧位置回灌；对拖动后几秒内出现的厂商播放器错误完成回调直接忽略，不再重复 seek、重建数据源、误跳下一集、显示“最后一集”或黑屏。
- 新增准备/缓冲/拖动时的百分比和实时网速显示；百分比按当前读取窗口重新计算并限制在真实完成前不显示 100%，播放、暂停、完成或详情页小窗稳定后立即移除加载层，修复 0%/99% 假卡住和画面上残留网速的问题。
- 全屏底部菜单新增独立“字幕 开/关”按钮，字幕默认开启；关闭时只取消当前系统字幕、TimedText 或兼容播放器字幕轨道，不销毁字幕视图，重新开启或手动选字幕可以可靠恢复。
- 字幕初始化改为首帧/播放就绪后执行，系统轨道只允许一次补查，并避免逐轨取消后再选择；修复字幕时有时无、重复双层字幕以及轨道查询阻塞主线程的问题。
- 详情页在“播放地址”上方新增“正在播放”视频文件名，长文件名使用无限横向滚动；恢复历史集数、切换集数或线路时，文件名和地址始终跟随当前实际集数。
- 音频直通改为同时检查片源编码和 HDMI / ARC / eARC / 数字输出能力：设备支持的 AC-3、E-AC-3、E-AC-3 JOC、DTS、TrueHD 才直通，不支持的格式交给电视解码后仍由系统音频路由输出到外置设备；不再强制指定可能静音的内置扬声器设备。
- 修复准备、首帧、seek 和重建数据源期间的音频状态：保持媒体音频属性和满幅应用音量，保留已选音轨，并为 java64 缺失音轨信息提供一次受控的数据源恢复。
- 缩短并可取消播放前探测，复用探测到的首段数据，限制内存预热缓存；播放器释放、旧请求回调和全屏布局同步均按代际隔离，减少主线程阻塞、卡顿和掉帧，同时保留现有动画效果。
- 三端共享同一套播放、字幕和性能修复，并保留 java64 触控/手势/焦点链、Hisense 独立包名与 32 位 ABI。正式版本统一为 `0.2.2`（`versionCode 2026`），继续使用原签名，可覆盖安装 `0.2.1` 及 `0.2.1.x` 测试版。

### English

- The final in-version hotfix fixes playback freezing after the first frame. A transient empty `206` range response or prematurely-ended body at a cache boundary is now reopened within a cancellable deadline instead of being reported to the native decoder as EOF. Foreground reads and background prefetch use the previously validated handoff again rather than cancelling read-ahead on every cache miss.
- The 32-bit build now uses 4 MB prefetch chunks while retaining a 24 MB startup target, 32 MB forward target, and 40 MB total cache cap. This reduces large-object GC pressure and UI stalls, while a rendered first frame permanently suppresses stale detail-page loading overlays from older playback generations.
- Subtitle track merging, repeated seek handling, player teardown, and progress persistence were tightened; the empty-style home crash was fixed; runtime and disk log writing were disabled. The same fixes ship across all variants without changing java64 touch behavior or the Hisense package/ABI boundary.
- Rebuilt native-player startup and read-ahead so reads begin immediately while continuous range windows and background prefetch maintain a large forward buffer. Read-ahead no longer gates playback or raises its own timeout error; real prepared, buffering, and first-frame callbacks control playback state.
- Unified initial resume and normal timeline seeks under a single `SeekCoordinator`. A new scrub target immediately supersedes the previous target instead of queueing intermediate positions, prepared playback can start while its resume seek completes, and seeking while paused stays paused.
- Fixed progress persistence and false completion after seeking. The newest in-flight target takes precedence over stale native positions, while spurious vendor completion callbacks shortly after a seek are ignored without another seek or source rebuild.
- Added real buffering percentage plus network speed for prepare, rebuffer, and seek states. Progress is reset per range window, capped below 100 until actually complete, and removed immediately after stable playback, pause, completion, or embedded-preview recovery.
- Added a dedicated fullscreen subtitle on/off control, enabled by default. Disabling subtitles clears only the active native TimedText/subtitle or compatibility-player track and keeps the subtitle view reusable.
- Deferred subtitle discovery until the first frame or playback-ready state, limited native track discovery to one retry, and removed deselect-every-track behavior to prevent missing, duplicated, or main-thread-blocking subtitle initialization.
- Added a marquee “Now playing” filename above the playback URL on the detail page, kept in sync with restored history, episode changes, and source changes.
- Passthrough now checks both stream encoding and HDMI / ARC / eARC / digital-output capability. Supported AC-3, E-AC-3, E-AC-3 JOC, DTS, and TrueHD formats can pass through; unsupported formats are decoded while Android keeps routing audio to the active external output.
- Stabilized media audio attributes, app volume, selected audio tracks, and the prepare/first-frame/seek/source-rebuild lifecycle, including one controlled missing-audio-track recovery for java64.
- Made preflight probes shorter and cancellable, reused warmed probe bytes, bounded memory warmup caches, and isolated stale callbacks/layout sync by playback generation to reduce main-thread stalls without reducing animation effects.
- Shipped the same playback, subtitle, and performance core across all three variants while preserving java64 touch/gesture/focus behavior and the Hisense package/ABI boundary. All builds use `0.2.2` (`versionCode 2026`) with the existing signing certificate for in-place updates.

## 0.2.1

### 中文

- 修复“文采”等 HLS 视频源在 32 位电视系统播放器上出现黑屏有声音、假首帧、一直准备播放、误报播放出错的问题。
- HLS 空轨道/未知轨道现在按视频源处理，等待真实视频输出目标和系统首帧回调，不再提前伪造渲染开始。
- 播放链路统一收紧为 `SurfaceView` + 硬解优先，删除软解兜底；兼容播放器硬解启动失败时直接上报真实错误，不再在系统/兼容播放器之间来回切换。
- 对不支持硬解的 `H.264 High10` 片源，直接提示“此格式不支持硬件解码（H.264 High10）”。
- 降低 HLS 预取、Range 数据源缓存和运行日志压力，减少低端电视上的空转、卡顿和播放页启动等待，并为页面进入、返回和退出补充轻量级 Apple TV / iOS 风格动画。
- 三端统一版本为 `0.2.1`，继续支持从 `0.1.9.1` / `0.2.0` 覆盖安装。

### English

- Fixed 32-bit TV system-player HLS cases such as Wen Cai where playback could fall into black-screen-with-audio, fake first-frame success, endless prepare, or false playback errors.
- Empty or unknown HLS track lists now stay on the video path and wait for a real output target plus the system first-frame callback instead of faking render start.
- Tightened playback to `SurfaceView` and hardware decode only, removed software-decode fallback, and now surface compatibility-player hardware startup failures directly instead of bouncing between players.
- Unsupported `H.264 High10` streams now fail explicitly with a hardware-decode-not-supported message.
- Reduced HLS prefetch, range-source cache, and runtime-log pressure to avoid low-end TV stalls and slow playback-page startup, and added lightweight Apple TV / iOS-style transitions for page entry, back, and exit.
- Unified all three deliverables under version `0.2.1`, preserving in-place updates from `0.1.9.1` / `0.2.0`.

## 0.2.0

### 中文

- 修复 32 位 TV 系统播放器在 HDR/DV、本地代理 MKV 等场景下因 safe-pcm 音频属性导致的黑屏有声音、播放失败或播放出错风险。
- 网络直连播放保留外部 `User-Agent`、`Referer`、`Origin`、`Cookie` 等请求头，减少需要鉴权/防盗链的视频源失败。
- Surface/Display 销毁或重建时不再把非致命 detach 异常当成播放错误，降低切屏、全屏切换、Surface 重建时的误报错。
- 三端统一版本为 `0.2.0`，并用仓库内 `_runtime` 本地环境构建。

### English

- Fixed 32-bit TV system-player risk where HDR/DV or local-proxy MKV playback could hit black-screen-with-audio, playback failure, or player errors from the old safe-pcm audio attributes.
- Preserved external `User-Agent`, `Referer`, `Origin`, and `Cookie` headers for direct network playback to reduce failures on protected video URLs.
- Treated Surface/Display detach failures during surface teardown or rebuild as non-fatal, reducing false playback errors during fullscreen or surface transitions.
- Unified all three deliverables under version `0.2.0` and built them with the repo-local `_runtime` environment.

## 0.1.3

### 中文

- 修正 `java64` 非电视设备的系统播放器渲染层：系统硬解默认改走 `TextureView`，避免 `SurfaceView` 覆盖触控层导致全屏无法单击、菜单无法呼出或手势失效。
- 为 `PlayActivity` 增加全屏触控顶层分发兜底，并补齐 `BaseController` 的 `OnDoubleTapListener` 注册，收紧 64 位手机 / 平板全屏触控链路。
- 收紧 32 位杜比视界路由：真实支持原生 DV 的设备不再被本地代理 MKV 预探测误降级；不支持原生 DV 但支持 HDR10 的设备，对双层 / HDR10 基础层 DV 优先走系统硬解 HDR10 基础层，降低灰屏风险。
- 保留真实支持原生杜比视界的 64 位设备优先走系统链路，避免本地代理 MKV / DV 在预探测不完整时被误降级到兼容映射链。
- 补充系统播放器音轨选择与运行日志，以及 MPV 在 64 位触屏设备上的音频安全模式，继续收敛“有画面没声音 / 音轨未正确选中”的问题。
- 统一三套 Android 包的发布版本为 `0.1.3`：`java32`、`java64`、`hisense32`。

### English

- Fixed the `java64` non-TV system-player render path by preferring `TextureView`, preventing `SurfaceView` from swallowing fullscreen taps, menu gestures, and touch interaction.
- Added activity-level fullscreen touch dispatch in `PlayActivity` and completed `OnDoubleTapListener` registration in `BaseController` to stabilize fullscreen controls on 64-bit phones and tablets.
- Tightened the 32-bit Dolby Vision route: true native-DV devices are no longer downgraded by incomplete local-proxy MKV probes, and HDR-capable non-native-DV devices now prefer the system-player HDR10 base layer for dual-layer / base-layer DV streams to reduce gray-screen cases.
- Kept true native-Dolby-Vision 64-bit devices on the system playback path so local-proxy MKV / DV streams are not downgraded to the compatibility mapping chain too aggressively.
- Added stronger system-player audio-track selection/logging and an MPV audio safe-mode branch for 64-bit touch devices to further reduce silent-playback edge cases.
- Unified the three Android deliverables under release `0.1.3`: `java32`, `java64`, and `hisense32`.

## 0.1.1

### 中文

- 修复 `PlayActivity` 与 `PlayFragment` 的 `sourceKey / progressKey` 不一致问题，减少切源后 `key=null`、重播和进度恢复错误。
- 为本地 `proxy/play` MKV / HDR / DV 预探测补上更积极的字节级快速探测，降低探测超时后错误降级到 SDR 路径的概率。
- 收紧 Dolby Vision 本地代理识别条件，避免普通本地代理 MKV 被误判成原生 DV 路由。
- 补齐 `PlayActivity` 的 WebView / m3u8 代际隔离，避免旧回调在切源或重进播放页后回灌到新的播放请求。
- 重新构建并整理三套 Android 包：`java32`、`java64`、`hisense32`。

### English

- Fixed `sourceKey / progressKey` drift between `PlayActivity` and `PlayFragment`, reducing `key=null`, replay, and resume mismatches after source switching.
- Added a stronger byte-level fast probe for local `proxy/play` MKV / HDR / DV streams so timeout cases are less likely to fall back to an SDR route.
- Tightened Dolby Vision detection for local proxy playback to reduce false native-DV routing on plain MKV streams.
- Added generation-safe WebView and m3u8 callback isolation in `PlayActivity` so stale parse callbacks cannot overwrite a newer playback request.
- Rebuilt and packaged the three Android variants: `java32`, `java64`, and `hisense32`.

## 0.1

### 中文

- 整理为三个独立构建：`java32`、`java64`、`hisense`
- 版本号统一为 `0.1`
- 仓库首页改为中英文说明
- 增加适合公开仓库的基础 Android 构建工作流
- 播放架构保留系统播放器优先，并补充 MKV / Dolby Vision 的兼容链路

### English

- Consolidated into three build variants: `java32`, `java64`, and `hisense`
- Unified project version to `0.1`
- Reworked the repository front page with Chinese and English documentation
- Added a public-repo friendly Android build workflow
- Preserved the native system-player-first architecture and the MKV / Dolby Vision compatibility path
