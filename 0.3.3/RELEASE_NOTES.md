# TVBox 0.3.3

`versionName 0.3.3` · `versionCode 30003`

## 中文

### 本次更新

0.3.3 针对高堆 64 位设备的连续播放余量和 Range 下载开销做了分层优化，同时保留 32 位设备的保守内存边界。直播/HLS 的独立流式缓存策略没有被改动。

- Java 堆不超过 `640 MiB` 的设备使用 `192 MiB` 原始 Range 缓存；高于 `640 MiB` 使用 `288 MiB`；达到 `1 GiB` 使用 `384 MiB`。
- 32 位和低堆设备保持 `192 MiB` 上限、单预取通道和单探测线程，关闭相邻并发预取，降低 GC/OOM 风险。
- 高堆 64 位进程级预算与设备堆分层同步，并保留 `128 MiB` 给解码器、解封装器、字幕和 UI；32 位/低堆设备继续使用 `96 MiB` 的保守余量。缓存只保存有界窗口，不会按视频总大小分配内存。
- Range payload 由 `64 KiB` 调整为 `256 KiB` 分片，减少短生命周期对象和 GC 压力；6677 Range 请求使用 HTTP/1.1 连接复用。
- Range 错误日志记录有效字节、请求耗时、实时 bytes/s、预取和重试结果，正常播放不产生高频日志。

### 发布包

| 文件 | ABI |
| --- | --- |
| `TVBox_v0.3.3_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.3_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.3_hisense32.apk` | `armeabi-v7a` |

三端沿用历史正式签名，证书 SHA-256 为 `7c05de517bc51e22f782b2369704c74d459aa15d4e0074f1f2c3491cc76d6bbc`，可覆盖安装相同变体。发布包附带 `SHA256SUMS.txt`。

### 验证范围与限制

播放器定向测试覆盖滚动缓存、暂停后补充窗口和重复 Range 防护；发布前应完成完整播放器测试、三端 Release 构建以及版本号、ABI、非 debuggable、签名和 SHA-256 校验。当前没有在线 ADB 设备，因此本版本不宣称电视或手机端到端实测通过。实际吞吐仍受源站、代理、网络、设备调度和视频码率限制，软件不能保证固定的 10--20 MB/s。

## English

### What changed

Version 0.3.3 tiers the Range cache for high-heap 64-bit devices while keeping conservative limits on 32-bit devices. The independent streaming policy for live/HLS playback is unchanged.

- Devices with a Java heap of `640 MiB` or less use a `192 MiB` raw Range cache; devices above `640 MiB` use `288 MiB`; devices at or above `1 GiB` use `384 MiB`.
- 32-bit and low-heap devices retain a `192 MiB` ceiling, one prefetch lane, and one bounded probe lane. Sibling prefetch is disabled to reduce GC and OOM risk.
- High-heap 64-bit process budgets follow the same tiers and reserve `128 MiB` for decoders, demuxers, subtitles, and UI; 32-bit/low-heap devices retain a conservative `96 MiB` headroom. Memory is limited to a rolling window and never allocated for the whole source file.
- Range payload slices are `256 KiB` instead of `64 KiB`, reducing short-lived objects and GC pressure. The `6677` Range path reuses an HTTP/1.1 connection.
- Error-only Range diagnostics record effective bytes, request time, bytes per second, prefetch, and retry outcomes without producing high-frequency normal-playback logs.

### Packages

| File | ABI |
| --- | --- |
| `TVBox_v0.3.3_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.3_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.3_hisense32.apk` | `armeabi-v7a` |

All variants retain the historical release certificate, SHA-256 `7c05de517bc51e22f782b2369704c74d459aa15d4e0074f1f2c3491cc76d6bbc`, and support in-place updates of the same variant. The release includes `SHA256SUMS.txt`.

### Validation scope and limits

Focused player tests cover rolling-cache consumption, paused-source refill, and duplicate Range protection. Before publication, run the complete player suite, all three Release builds, and version, ABI, non-debuggable, certificate, and SHA-256 checks. No ADB device is currently online, so this release does not claim an end-to-end TV or phone test. Actual throughput remains dependent on the source, proxy, network, device scheduling, and video bitrate; the app cannot guarantee a fixed 10--20 MB/s.
