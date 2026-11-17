# TVBox 0.3.6

`versionName 0.3.6` · `versionCode 30006`

## 中文

### 缓冲进度与播放放行

- 点播和 WebDAV 的进度显示只统计连续、已提交到滚动缓存的数据。达到当前播放位置的启播门槛后才显示 `100%` 并开始播放，避免在途响应把进度提前推满后播放器继续等待。
- 播放器渲染首帧时完成当前缓冲 episode，并清理速度/百分比加载提示。短文件、尾部播放和恢复点不再因为固定 `96 MiB` 目标而停在错误的百分比。
- 直播/HLS 继续使用独立的流式策略，本版本的剩余字节进度基准只作用于点播和 WebDAV Range 路径。

### Seek overlay

- 恢复播放、向前 Seek 和向后 Seek 都以新的播放位置作为进度锚点，按锚点之后的剩余实体长度计算可见百分比。
- 每次新播放代次都会使已排队的旧 UI 更新失效。旧的 `100%`、速度或加载文本不会在视频已经恢复后重新覆盖画面。
- 播放与加载回调继续在主线程安全更新，不改变音频质量、HDR 路由或三端 ABI 边界。

### 发布包与验证边界

| 文件 | ABI |
| --- | --- |
| `TVBox_v0.3.6_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.6_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.6_hisense32.apk` | `armeabi-v7a` |

三端沿用历史正式签名，支持同变体覆盖安装。发布包应附带 `SHA256SUMS.txt`。发布前执行播放器缓存/进度/Seek overlay 测试、三端构建，以及版本号、ABI、非 debuggable、签名和 SHA-256 校验。实际连续播放仍取决于源站吞吐、网络、设备调度和视频码率。

## English

### Buffer progress and start gate

- VOD and WebDAV progress counts only contiguous bytes committed to the rolling cache. The overlay reaches `100%` and playback starts only after the runway at the current position satisfies the startup gate; in-flight responses cannot make the UI appear complete while the player is still waiting.
- Rendering the first frame completes the current buffering episode and clears its speed/percent overlay. Short files, tail playback, and resume points no longer stop at a misleading percentage because of the fixed `96 MiB` target.
- Live/HLS retains its independent streaming policy. The remaining-bytes denominator applies only to VOD and WebDAV Range playback.

### Seek overlay

- Resume, forward seek, and backward seek use the new playback position as the progress anchor and calculate the visible percentage from the remaining entity length after that anchor.
- Each new playback generation invalidates queued UI updates. Old `100%`, speed, or loading text cannot cover the video after playback has resumed.
- Playback and loading callbacks remain main-thread safe; audio quality, HDR routing, and the three ABI boundaries are unchanged.

### Packages and validation boundary

| File | ABI |
| --- | --- |
| `TVBox_v0.3.6_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.6_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.6_hisense32.apk` | `armeabi-v7a` |

All variants retain the historical release certificate and support in-place updates of the same variant. The release should include `SHA256SUMS.txt`. Before publication, run player cache/progress/Seek overlay tests, all three builds, and version, ABI, non-debuggable, signing, and SHA-256 checks. Continuous playback still depends on source throughput, network, device scheduling, and video bitrate.
