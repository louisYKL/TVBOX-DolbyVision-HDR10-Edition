<div align="center">

# TV BOX 海信 32 位版

> 基于 `0.2.8` 播放核心同步的海信 Android / Google TV 独立分支。

<p>
  <a href="https://github.com/louisYKL/TVBOX-DolbyVision-HDR10-Edition/releases/tag/v0.2.8"><img alt="Release" src="https://img.shields.io/badge/release-v0.2.8-white?style=for-the-badge&labelColor=111111&color=F5F5F5"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Hisense%20Android%20TV-white?style=for-the-badge&labelColor=111111&color=F5F5F5">
</p>

</div>

## 当前版本

- 包名：`com.github.tvbox.osc.hisense`
- 版本号：`0.2.8`（`versionCode 2080`）
- ABI：`armeabi-v7a`
- 输出 APK：`TVBox_v0.2.8_hisense32.apk`
- 最低系统：Android 4.4 / API 19

## 0.2.8 同步内容

- 受控应用内 `do=m3u8` / `proxyM3u8` 源统一通过 `go=live&type=m3u8` 路由 HLS 播放列表和媒体分片，避免本地 HLS 地址被错误当作普通直连流。
- URL 规范化保留嵌套签名地址的原始转义层级，避免 `%2B`、`%25` 等编码被二次解码后失效。
- HLS 分片使用独立 HTTP/1.1 连接，并按系统 DNS、配置 DNS、系统 DNS 的有限顺序恢复；无 `.m3u8` 后缀的子播放列表会正确识别为播放列表而不是媒体分片。
- 最终 HTTP 失败会受控结束当前 HLS 请求，不再让系统播放器对同一播放地址无限重建；既有大文件 Range、进度恢复、`416` 和连续拖动恢复边界保持不变。
- 保留海信独立包名 `com.github.tvbox.osc.hisense`、`armeabi-v7a` ABI、设备硬解、立体声 PCM、HDR 字幕和原签名；`0.2.8`（`versionCode 2080`）可覆盖安装 `0.2.7` 及更早版本，并可与主版并存。

## 0.2.7 同步内容

- 修复本地 `6677/proxy/play` 在恢复进度的非零 Range 请求中偶发返回“PNG 文件头 + EBML 视频头”的污染正文；数据源会丢弃整段错误响应并重试原始 Range，不会把图片头交给 Matroska 解码器，避免缓冲 0%、黑屏或播放超时。
- 保持系统硬解单一前台 Range 读取链、`8 MiB` 首包/前台窗口、最大 `16 MiB` 窗口和大文件持续缓存；异常 Range 不会破坏正常首播、恢复进度或连续拖动。
- 收紧源重试次数和活动缓冲边界，避免异常响应无限重试，同时保留临时网络失败的自动恢复。
- 三端继续保持海信独立包名 `com.github.tvbox.osc.hisense`、`armeabi-v7a` ABI、字幕/音频/HDR 路由和原签名，可覆盖安装 `0.2.6`。

## 0.2.6 同步内容

- 系统硬解改为单一前台 Range 读取链，首包和前台窗口保持 `8 MiB`、最大窗口 `16 MiB`，不与后台 Range 预读竞争，打开视频立即开始真实读取。
- 精确兼容已确认的 `6677/proxy/play` 八字节异常 Range；普通短包、空包、偏移错误和 `416` 仍严格重试，避免错误窗口、0% 假进度和播放超时。
- 恢复进度、连续快进/回退、缓冲百分比和实时网速同步到海信端，保持大文件持续缓存；首帧/缓冲结束后清理过期加载文字、旋转图标和 seek 提示。
- HDR 位图字幕使用灰色字形和黑色描边，SDR 保持白色字形；保留 `com.github.tvbox.osc.hisense`、Android 4.4 最低版本和 `armeabi-v7a` ABI。`0.2.6`（`versionCode 2060`）沿用原签名，可覆盖安装 `0.2.5` 及更早版本并与主版并存。

## 构建

```powershell
.\build-local.ps1
```

构建环境、SDK、JDK、Gradle 缓存全部来自仓内 `_runtime`，不依赖仓外目录。

## 兼容说明

- 本分支只针对海信 Android / Google TV 机型。
- VIDAA 机型不是 Android APK 路线，不在这个包的支持范围内。
- 保留独立包名，可与主版 `com.github.tvbox.osc` 并存安装。
