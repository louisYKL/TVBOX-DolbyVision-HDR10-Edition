# TVBox 0.3.5

`versionName 0.3.5` · `versionCode 30005`

## 中文

### Dolby Vision 路由

- 电视或手机只有显示面板支持 Dolby Vision、而芯片没有可用的端到端 DV 解码器时，播放路由使用视频自身已验证的 HDR10、HLG 或 SDR 兼容基础层。显示能力不会替代解码能力。
- 普通 `video/hevc` 轨道也会结合 codec 字符串、配置记录、视频轨道信息和有界探测结果识别 Dolby Vision，不再只依赖 `video/dolby-vision` MIME。
- 对真实可用且 profile/level 匹配的原生 DV 解码器仍保留原生路径；无法证明原生路径完整可用时，避免把基础层送入 DV 解码器造成黑屏伴音。

### RPU/EL 与探测缓存

- RPU 和 EL NAL 清理支持 Annex-B 起始码和 2/4 字节大端长度前缀样本，覆盖常见 ISO-BMFF/Matroska HEVC 访问单元。
- 清理器先验证一个完整访问单元和 NAL 长度，再压缩输出；长度解析失败或没有确认 RPU/EL 时保留原始样本，不破坏普通 HEVC。
- 缺少 profile、level、视频轨道或兼容基础层证据的弱 Dolby Vision 探测结果不会进入可复用缓存。后续播放继续使用有界确认探测，避免一次不完整的文件前缀探测导致错误的“不支持 DV”结果。

### 发布包与验证边界

| 文件 | ABI |
| --- | --- |
| `TVBox_v0.3.5_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.5_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.5_hisense32.apk` | `armeabi-v7a` |

三端沿用历史正式签名，支持同变体覆盖安装。发布包应附带 `SHA256SUMS.txt`。发布前执行 Dolby Vision 路由、RPU/EL 清理、探测缓存、播放器和应用测试，并核验版本号、ABI、非 debuggable、签名与 SHA-256。是否实际进入设备 DV 输出模式仍取决于设备解码器、系统显示管线、面板和源视频 profile；软件不能为不支持的硬件增加 DV 能力。

### 验证边界

- 三端应用单元测试通过，APK 的版本、ABI、签名、非 debuggable 状态和 SHA-256 已核验。
- `:player:testReleaseSimonUnitTest` 已通过，包含 `startupPrefetchUsesHttpToFillBridgeAfterFragmentedCache` 和 `systemRangeDoesNotStartCompetingBackgroundPrefetch`。

## English

### Dolby Vision routing

- If a TV or phone panel advertises Dolby Vision but the chip has no usable end-to-end DV decoder, route playback through the source's verified HDR10, HLG, or SDR compatibility base. Display capability does not replace decode capability.
- Ordinary `video/hevc` tracks are classified using codec strings, configuration records, video-track evidence, and bounded probe results. A `video/dolby-vision` MIME declaration is no longer required.
- A native route remains available only when a real decoder matches the stream's profile and level. When that end-to-end route cannot be proven, the base layer is kept away from a DV decoder to avoid an audio-only black surface.

### RPU/EL and probe cache

- RPU and EL NAL stripping supports both Annex-B start codes and 2/4-byte big-endian length-prefixed samples used by common ISO-BMFF/Matroska HEVC access units.
- The stripper validates a complete access unit and each NAL length before compacting output. If validation fails, or RPU/EL is not confirmed, the original sample is preserved so ordinary HEVC is not damaged.
- Weak Dolby Vision probe results that lack a profile, level, video track, or compatibility-base evidence are not persisted in the reusable cache. A bounded confirmation probe can resolve the route on the next attempt instead of preserving an incomplete unsupported-DV decision.

### Packages and validation boundary

| File | ABI |
| --- | --- |
| `TVBox_v0.3.5_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.5_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.5_hisense32.apk` | `armeabi-v7a` |

All variants retain the historical release certificate and support in-place updates of the same variant. The release should include `SHA256SUMS.txt`. Before publication, run Dolby Vision routing, RPU/EL stripping, probe-cache, player, and app tests, then verify version, ABI, non-debuggable status, signing certificate, and SHA-256. Actual DV output still depends on the device decoder, display pipeline, panel, and source profile; software cannot add unsupported hardware capability.

### Validation boundary

- The three application unit-test suites passed. APK version, ABI, signing certificate, non-debuggable status, and SHA-256 were verified.
- `:player:testReleaseSimonUnitTest` passed, including `startupPrefetchUsesHttpToFillBridgeAfterFragmentedCache` and `systemRangeDoesNotStartCompetingBackgroundPrefetch`.
