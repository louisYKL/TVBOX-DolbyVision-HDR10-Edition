# Changelog

## 0.2.2

### 中文

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
