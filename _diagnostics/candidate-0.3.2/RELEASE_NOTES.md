# TVBox 0.3.2

`versionName 0.3.2` · `versionCode 30002`

## 中文

本版本专注于 WebDAV HTTPS 兼容性，三端共用同一套地址解析、目录浏览、视频播放和外挂字幕下载逻辑。现有 HDR、缓存、音频、字幕渲染和 9978 管理服务行为保持不变。

### WebDAV HTTPS

- 支持 `https://` 域名、IPv4 地址和 IPv6 地址，例如 `https://dav.example.com/dav/`、`https://192.168.0.116/dav/` 和 `https://[2001:db8::116]/dav/`。
- HTTPS 地址省略端口时使用标准 TLS 端口 `443`。未写端口的 HTTP 地址继续使用现有 AList/WebDAV 默认端口 `5244`。
- 显式端口始终保留，因此 `https://host:5244/dav/` 等自定义部署仍可使用。
- WebDAV `PROPFIND` 目录浏览、视频 URL、同目录字幕下载和播放器认证均保留 HTTPS scheme、主机和端口；目录响应中的相对路径和绝对路径仍限制在配置的同一 HTTPS 服务器和根目录内。
- 使用 Android/OkHttp 默认证书链验证 HTTPS。无效、过期、主机名不匹配或自签名证书不会被静默接受；请为服务器配置设备信任的证书，或使用明确受信任的证书链。

### 发布包与验证范围

| 文件 | ABI |
| --- | --- |
| `TVBox_v0.3.2_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.2_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.2_hisense32.apk` | `armeabi-v7a` |

三端沿用历史正式签名 `7c05de517bc51e22f782b2369704c74d459aa15d4e0074f1f2c3491cc76d6bbc`，支持同变体覆盖安装。发布前执行 WebDAV 地址单元测试、播放器单元测试、应用单元测试、三端 Release 构建、版本/ABI/非 debuggable/证书/SHA-256 核验。未连接设备进行端到端 HTTPS 服务器实测；实际连接还取决于服务器证书、认证方式、网络和设备 TLS 能力。

## English

This release focuses on WebDAV HTTPS compatibility. All three variants share the same address normalization, directory browsing, video playback, and external subtitle download code. Existing HDR, buffering, audio, subtitle rendering, and port-9978 behavior is retained.

### WebDAV HTTPS

- Accept `https://` hostnames, IPv4 addresses, and IPv6 addresses, including `https://dav.example.com/dav/`, `https://192.168.0.116/dav/`, and `https://[2001:db8::116]/dav/`.
- HTTPS addresses without a port use the standard TLS port `443`. HTTP addresses without a port retain the existing AList/WebDAV default `5244`.
- Explicit ports are preserved, so deployments such as `https://host:5244/dav/` continue to work.
- WebDAV `PROPFIND` listings, video URLs, sibling subtitle downloads, and player authorization retain the HTTPS scheme, host, and port. Relative and absolute directory response paths remain restricted to the configured HTTPS server and root path.
- Android/OkHttp's normal certificate chain validation remains enabled. Invalid, expired, hostname-mismatched, and self-signed certificates are not silently accepted; configure a certificate chain trusted by the device.

### Packages and verification scope

| File | ABI |
| --- | --- |
| `TVBox_v0.3.2_java32.apk` | `armeabi-v7a` |
| `TVBox_v0.3.2_java64.apk` | `arm64-v8a` |
| `TVBox_v0.3.2_hisense32.apk` | `armeabi-v7a` |

All variants retain the historical release certificate `7c05de517bc51e22f782b2369704c74d459aa15d4e0074f1f2c3491cc76d6bbc` for in-place updates. Before publication we run WebDAV address unit tests, player and app unit tests, all three Release builds, and version/ABI/non-debuggable/certificate/SHA-256 checks. No device was connected for end-to-end HTTPS server testing; actual connections also depend on the server certificate, authentication, network, and device TLS support.
