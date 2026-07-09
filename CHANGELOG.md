# Changelog

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
