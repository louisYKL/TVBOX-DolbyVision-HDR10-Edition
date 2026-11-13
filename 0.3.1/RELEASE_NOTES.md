# TVBox 0.3.1

`versionName 0.3.1` · `versionCode 30001`

## 中文

本次更新修复“已识别 HDR / Dolby Vision，但未把正确色彩信号交给解码器与播放窗口”的链路问题，重点覆盖手机上的格式元数据丢失、Dolby Vision 配置解析和窗口生命周期。32 位、64 位和海信版继续使用同一套核心实现与既有正式签名。

### HDR10、HDR10+ 与 HLG

- 视频探测结果携带实际色彩传递函数、色域、全/有限范围及源文件已有的静态 HDR 描述，随播放头信息传递到共享硬件解码器。源数据补齐缺失字段，已有明确 SDR 信息不会被普通 HDR 标记覆盖。
- MP4 `colr/nclx`、Matroska `Colour`、HLS `VIDEO-RANGE` 以及设备 `MediaExtractor` 的 PQ/HLG 信号分别保留；HLG 不再被笼统的 HDR 标记改写成 PQ。BT.2020、Main10 或 10 bit 本身不作为激活 HDR 的充分依据。
- 解码配置沿用真实源参数，不再生成固定 1000 nit 的静态母版信息来强行激活 HDR。保留设备支持的硬件 HDR 解码路径，包括部分未正确上报硬件标志的厂商解码器别名，排除明确的软件解码器。
- HDR10+ 的动态元数据仍由实际视频样本和硬件解码链路处理，不把动态帧数据伪装成静态文件头。HDR 亮度、色调映射和面板输出仍由系统及设备负责。

### Dolby Vision

- 修复 `dvcC` / `dvvC` 配置记录的位字段读取，正确取得 profile、level 和基础层兼容 ID；修复 `dvhe.08.06` 等 codec 字符串误解析 profile 的问题。
- 真实 DV 配置被封装/提取器丢失时，仅在已取得实际 profile 和 level 的情况下恢复 Dolby Vision 信令，不凭空构造配置。设备暴露真实 DV 硬件解码器时先保留原生候选，再由实际 profile/level 的解码能力检查决定；显示字段缺报 DV 时不提前降级为基础层。
- 原生 DV 不剥离 RPU 动态元数据。切到普通 HEVC 基础层时，仅按已确认的基础层兼容信息处理信令和 RPU，不对所有 DV 视频统一转成 HDR10。
- 兼容基础层按源配置区分：Profile 7 和 Profile 8 / compatibility ID 1 对应 HDR10，Profile 8 / ID 4 对应 HLG，Profile 8 / ID 2 和 Profile 9 的 AVC 基础层对应 SDR。Profile 5 的 IPTPQc2 不是普通 HDR10 基础层，不再强行按 PQ YCbCr 输出。
- 系统属性或 XML 中“声明支持 DV”不代替可用解码器与显示能力检查。最终是否可用还取决于该设备对具体 profile、level、封装和码流的支持。

### 播放窗口与手机显示

- 显示能力从播放 Activity 当前所属的显示器查询；仅缓存较稳定的解码器信息，避免首次默认屏幕结果长期覆盖外接屏、折叠屏或显示模式变化。
- 通过公开 Window HDR 色彩模式 API 和窗口参数提交请求，在真正附着到窗口或重新获得焦点时再次确认；用会话代次排除过期请求，退出后移除监听并恢复默认模式。
- 仅在主线程真正应用请求后确认窗口接受状态。窗口接受 HDR 请求与面板实际输出是不同信息，分辨率/HDR 角标也仅描述已识别的视频格式。
- 不设置屏幕亮度或修改系统亮度设置。继续保留纯文字 HDR 字幕的浅灰字/黑色描边，保留有源样式和位图字幕的设计。

### 直播启动与既有策略

- 独立 `:player` 直播进程使用主进程通过 Intent 传入的最新已保存直播地址，避免进程内旧配置导致源为空或仍播放旧地址。
- 移除加载前的过期空列表阻断及构造阶段清空数据行为；没有源时仍可返回首页和设置，不自动跳到直播。
- 列表类型按实际内容识别，不以 URL 是否包含特定关键词决定解析器。
- 点播 Range 块仍为 `24 MiB`，启播目标仍为当前播放点之后连续 `96 MiB`，原始缓存配置上限仍为 `192 MiB`，并受实际堆余量约束。直播保持独立的流式缓存。
- 音频解码及输出策略、WebDAV、字幕浏览、9978 五项独立远程输入继续保留。本次不通过降低音频质量改变 HDR 输出或掩盖缓冲问题。

### 发布包与验证范围

| 文件 | ABI |
| --- | --- |
| `TVBox_v0.3.1_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.1_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.1_hisense32.apk` | `armeabi-v7a` |

正式包沿用历史签名以支持同变体覆盖安装；发布时核对实际 APK 的版本、ABI、非调试标记、证书及 SHA-256，并提供 `SHA256SUMS.txt`。

自动化验证已完成：`player:testDebugUnitTest` 通过 164 项，`app:testNormalDebugUnitTest` 通过 209 项，三端 Release 构建成功。APK 已核对版本 `0.3.1` / `30001`、ABI、非调试标记、历史签名证书 `7c05de517bc51e22f782b2369704c74d459aa15d4e0074f1f2c3491cc76d6bbc` 和 SHA-256。9978 网页输入回归检查通过 18 项。本轮未连接可用 ADB 设备，未实测全部电视/手机的 HDR10、HDR10+、HLG 或 Dolby Vision 面板输出；不能把逻辑测试、成功构建或窗口请求接受等同于所有设备实测通过。实际输出需要设备固件、硬件解码器、显示器与源格式同时支持。

## English

This release repairs the path between identifying HDR / Dolby Vision and handing its real color signaling to the decoder and playback window. It focuses on metadata loss, Dolby Vision configuration parsing, and window lifecycle behavior on phones. All three variants retain shared core code and the existing release certificate.

### HDR10, HDR10+, and HLG

- Probe results retain the source transfer function, color standard, range, and existing static HDR descriptor, then carry them through playback headers to the shared hardware decoder. Source evidence fills missing fields without overriding unambiguous SDR metadata.
- Preserve distinct PQ/HLG signaling from MP4 `colr/nclx`, Matroska `Colour`, HLS `VIDEO-RANGE`, and platform `MediaExtractor`. Generic HDR flags no longer turn HLG into PQ. BT.2020, Main10, and 10-bit depth alone are not sufficient HDR evidence.
- Configure decoders with source-owned values instead of synthesizing a fixed 1000-nit mastering descriptor. Retain hardware HDR paths, including conservative vendor aliases that omit their hardware flag, while rejecting explicitly software-only decoders.
- HDR10+ dynamic metadata remains in the actual samples and hardware pipeline; it is not fabricated as static headers. The system and device continue to control HDR brightness, tone mapping, and panel output.

### Dolby Vision

- Correct the bit-field parsing of `dvcC` / `dvvC` records to read the actual profile, level, and base-layer compatibility ID. Fix profile parsing for codec strings such as `dvhe.08.06`.
- Restore missing Dolby Vision signaling only when the actual profile and level are available. Retain a native candidate when an actual hardware DV decoder exists, then check the actual profile/level capabilities. Missing DV display reporting does not preemptively force a base-layer fallback.
- Keep dynamic RPU metadata on native DV paths. Strip or adapt DV signaling only for an identified compatible ordinary-HEVC base layer, instead of treating all Dolby Vision as HDR10.
- Distinguish real bases: Profile 7 and Profile 8 / compatibility ID 1 use HDR10; Profile 8 / ID 4 uses HLG; Profile 8 / ID 2 and the Profile 9 AVC base use SDR. Profile 5 IPTPQc2 is not an ordinary HDR10 base and is not forced into PQ YCbCr output.
- XML or system-property declarations do not replace checks for an available codec and display support. Native playback still depends on the device's support for the actual profile, level, container, and bitstream.

### Playback windows and phone displays

- Query the display currently hosting the playback Activity. Cache only the more stable decoder information, so the first default-display result does not permanently override external, foldable, or changed display modes.
- Submit HDR color-mode requests through both public Window and layout-parameter APIs. Reassert on actual attachment and focus restoration; generation checks ignore stale requests, and teardown removes listeners and restores default mode.
- Report window acceptance only after applying the request on the main thread. An accepted request is distinct from measured panel output, and resolution/HDR badges describe the identified video format.
- Do not set screen brightness or modify system brightness. Keep light-gray/black-outline styling for unstyled HDR text, while retaining source-styled and bitmap subtitles.

### Live startup and retained policies

- Pass the latest saved live URL from the main process to the standalone `:player` live Activity through its Intent, avoiding stale process-local configuration or empty-source startup.
- Remove stale empty-list preconditions and constructor-time clearing. Missing sources do not prevent returning to Home and Settings, and startup does not automatically select live playback.
- Detect playlist formats from content rather than URL keywords.
- Retain `24 MiB` VOD Range chunks, a `96 MiB` contiguous startup target ahead of the playback point, and a configured `192 MiB` raw-cache ceiling constrained by actual heap headroom. Live retains independent streaming buffers.
- Preserve audio policy, WebDAV, subtitle browsing, and the five independent port 9978 inputs. This release does not lower audio quality to change HDR output or conceal buffering issues.

### Packages and verification scope

The release contains `TVBox_v0.3.1_java32.apk` (`armeabi-v7a`), `TVBox_v0.3.1_java64.apk` (`arm64-v8a`), and `TVBox_v0.3.1_hisense32.apk` (`armeabi-v7a`). Packages retain the historical certificate for same-variant updates. Publication checks the actual APK version, ABI, non-debuggable flag, certificate, and SHA-256, with `SHA256SUMS.txt` supplied for download verification.

Automated validation completed: `player:testDebugUnitTest` passed 164 tests, `app:testNormalDebugUnitTest` passed 209 tests, and all three Release variants built successfully. APKs were checked for version `0.3.1` / `30001`, ABI, non-debuggable status, the historical signing certificate `7c05de517bc51e22f782b2369704c74d459aa15d4e0074f1f2c3491cc76d6bbc`, and SHA-256. The port-9978 remote-input regression check passed 18 checks. No usable ADB device was connected this round, so actual HDR10, HDR10+, HLG, and Dolby Vision panel output across TVs and phones has not been exhaustively device-tested. Logic tests, successful builds, and accepted window requests are not evidence that every device outputs every format. Actual output requires matching firmware, hardware decoder, display, and source support.
