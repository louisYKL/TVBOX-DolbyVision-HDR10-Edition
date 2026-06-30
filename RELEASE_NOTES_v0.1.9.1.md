# TVBox 0.1.9.1 详细发布说明

## 发布概览

`0.1.9.1` 为一次紧急稳定性修复版，继续统一发布三条 Android 线：

- `java32`：32 位电视主线
- `java64`：64 位 Android 独立线
- `hisense32`：海信 32 位独立线

本次 release 重点只处理点播播放状态与进度恢复链路，不触碰直播链路和其它已经稳定的专有兼容逻辑。

## 本次 release 资产

- `TVBox_v0.1.9.1_java32.apk`
- `TVBox_v0.1.9.1_java64.apk`
- `TVBox_v0.1.9.1_hisense32.apk`
- `RELEASE_NOTES.md`
- `SHA256SUMS.txt`
- `TITLE.txt`

## 0.1.9 -> 0.1.9.1

### 1. 修复点播自动卡住与进度错误

- 修复点播视频播放过程中偶发自动卡住时，底部进度条和时间显示直接跳到片尾、与实际画面严重不符的问题。
- 修复异常状态下把视频进度误保存成“接近看完”或“已看完”的问题，避免下次播放直接从错误位置恢复。

### 2. 重构点播进度恢复状态机

- 将“真实播放位置”和“待恢复 / 待 seek 目标位置”彻底分离，避免 seek 目标被误当成真实播放进度。
- `seekTo()` 不再提前把目标位置写进当前进度缓存，只有底层播放器实际返回的位置才更新真实播放位置。
- 在准备完成、延迟恢复 seek、完成判定和手动持久化进度时统一走新的安全状态机。

### 3. 增加脏进度清理

- 当旧缓存进度明显异常、超过时长或贴近片尾时，启动播放前自动清理，避免一开播就进度错乱。
- `MyVideoView.persistProgressNow()` 仅保存已解析出的安全进度，不再把异常 seek 目标重新写回缓存。

### 4. 三端同步

- `java32`、`java64`、`hisense32` 三条线全部同步同一套点播进度恢复修复逻辑。
- 保持三条线源码边界独立，不把直播、音频兼容或厂商专项逻辑混入这次修复。

## 构建记录

- `TVBoxOS-main`：`:app:assembleNormalDebug`
- `TVBoxOS-java64`：`:app:assembleJava64Debug`
- `TVBoxOS-hisense`：`:app:assembleDebug`

## 版本信息

- 三端 `app/build.gradle` 已统一更新为：
  - `versionCode 1091`
  - `versionName 0.1.9.1`

## 产物对应关系

- `TVBoxOS-main` -> `TVBox_v0.1.9.1_java32.apk`
- `TVBoxOS-java64` -> `TVBox_v0.1.9.1_java64.apk`
- `TVBoxOS-hisense` -> `TVBox_v0.1.9.1_hisense32.apk`
