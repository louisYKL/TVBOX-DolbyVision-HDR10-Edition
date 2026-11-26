# TVBox 0.3.7

`versionName 0.3.7` · `versionCode 30007`

## 中文

### Seek 加载提示

- 修复 Seek 后厂商播放器的 buffering/seek 状态标记滞留，导致 `100%` 和速度提示一直覆盖视频的问题。
- 保留 Seek 提示的最短显示时间；播放器恢复时正常关闭，状态信号滞留时最多显示 30 秒后清理。
- Activity 全屏播放器与详情页内嵌播放器共用同一套纯策略，并为最短时间、正常恢复和超时路径加入单元测试。

### 杜比视界处理范围

- 系统 `MediaCodec` 继续负责硬件解码；当前 SurfaceView 直出链路没有暴露解码帧给 libplacebo。本版本不启用未验证的 libdovi/libplacebo 帧映射，也不改变现有原生 Dolby Vision 与 HDR 兼容层路由。
- 映射原型需要额外的 SurfaceTexture/EGL/GPU 输出通路，并验证设备合成器、HDR dataspace、性能和格式边界。窗口 HDR 请求本身不能保证电视进入 HDR，RPU 元数据也不能单独还原 DV Profile 7 增强层。

### 发布包与验证边界

| 文件 | ABI |
| --- | --- |
| `TVBox_v0.3.7_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.7_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.7_hisense32.apk` | `armeabi-v7a` |

三端沿用历史正式签名，支持同变体覆盖安装。播放器策略单测、三端 Release 构建、版本/ABI、非 debuggable、正式签名指纹和 SHA-256 均已通过，发布包附带 `SHA256SUMS.txt`。此版本修复不替代设备实测；连续播放仍受源站、网络、设备调度和视频码率影响。

## English

### Seek loading overlay

- Fix vendor player buffering/seek flags that could remain latched after a seek and leave the `100%`/speed overlay over the video.
- Keep a minimum visible duration, dismiss normally after playback resumes, and clear after at most 30 seconds if player signals remain stale.
- Share one pure policy between fullscreen Activity playback and embedded detail playback, with unit tests for the minimum duration, normal resume, and timeout paths.

### Dolby Vision processing scope

- System `MediaCodec` remains responsible for hardware decoding. The direct SurfaceView path does not expose decoded frames to libplacebo. This release does not enable unvalidated libdovi/libplacebo frame mapping and does not change the existing native Dolby Vision or HDR compatibility routes.
- A mapping prototype needs a separate SurfaceTexture/EGL/GPU output path and validation of the device compositor, HDR dataspace, performance, and format boundaries. A window HDR request alone cannot guarantee HDR output, and RPU metadata alone cannot reconstruct the Dolby Vision Profile 7 enhancement layer.

### Packages and validation boundary

| File | ABI |
| --- | --- |
| `TVBox_v0.3.7_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.7_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.7_hisense32.apk` | `armeabi-v7a` |

All variants retain the historical release certificate and support in-place updates of the same variant. Player policy tests, all three Release builds, version/ABI, non-debuggable, historical signing fingerprint, and SHA-256 checks passed. The release includes `SHA256SUMS.txt`. This fix does not replace device testing; continuous playback still depends on the source, network, device scheduling, and bitrate.
