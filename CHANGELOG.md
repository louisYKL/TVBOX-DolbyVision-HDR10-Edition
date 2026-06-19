# Changelog

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
