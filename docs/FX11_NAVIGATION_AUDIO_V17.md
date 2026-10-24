# FX11 高德 CarPlay 导航无声：v17 Opus 解码后备路径

## 已有实车证据

2026-10-16 保存的 FX11 诊断中，`audioType=default` 导航流采用 Opus：

```text
23:08:06.214 Audio: starting api=30 audioType=default codec=OPUS rate=48000 channels=1
23:08:06.220 Audio: renderer failed ... stage=decoder-create error=IllegalArgumentException ... at=android.media.MediaCodec.native_setup:-2
23:08:11.246 audio stats ... channel=NAVIGATION ... rx=252 ... playing=false ... writtenFrames=0 ... outputBuffersTotal=0
23:08:11.247 Audio: decoder stats ... inputQueuedTotal=0 ... decoderUnavailablePacketsTotal=252 outputBuffersTotal=0
```

来源为本地 `outputs/diplay-native-dashboard-v10/device/DiPlay-20261016-221519-808.txt` 的 750–766 行；v9 采样也出现相同故障。同期 AAC 音乐的播放位置持续增长，没有写入错误。

这确认了当时手机已经发送导航包，失败发生在 Android Opus 解码器创建阶段。日志不能区分固件没有注册可用解码器和注册的组件启动失败，也不能替代当前 v16 / 导航通道 2 的实车复测。v16 对这一失败仍没有软件解码后备路径。

设置里的测试音是本地生成的 48 kHz PCM，直接写入 AudioTrack，绕过了手机收包和 Opus 解码；因此测试音能响不代表实际导航可播放。改变输出通道不能修复解码器创建失败。

## 修复

- 优先使用已有系统 Opus MediaCodec。只有创建、配置或启动抛出异常时，改用项目已包含的 Concentus 软件解码器，无新增下载依赖。
- 输出保持 48 kHz、原声道数、16-bit little-endian PCM；不把 Opus RTP 的 16/24 kHz 时间戳时钟误当作输出采样率。
- 软件解码后的 PCM 进入原有 AudioTrack、缓冲、导航衰减和播放状态逻辑；继续使用用户的导航通道，例如 2，不自动修改通道或系统音量。
- 每条音频流持有独立解码器和可复用缓冲，最多容纳每包 120 ms，关闭流即释放引用。
- 有效的短 Opus 静音包允许进入解码器；空包不合成声音。非法包被丢弃，错误日志限三次，后续合法包仍可播放。
- 日志明确记录 `backend=software fallback=platform-startup-failed`、第一份已解码 PCM 和解码器 backend。日志不包含音频内容。
- AAC 音乐和 LPCM 处理不切换到 Opus 解码器；原有三套仪表及其他车辆适配代码保持不变。

## 验证与实车复测

自动测试覆盖真实 Opus 编解码、多种帧长/单双声道、有效三字节静音包、坏包后的恢复、独立状态及关闭、系统创建/启动失败后 PCM 写入导航通道 2、正常系统解码器优先、AAC 不误用软件 Opus。

本版未在车机上验证扬声器出声。覆盖安装并重新连接 CarPlay 后，可先保留音乐 0、导航 2，用高德触发一次播报。若仍无声，导出“设置 → 诊断 → 保存诊断报告”，核对：

1. `Audio: decoder ready ... backend=software`：是否启用后备解码器。
2. `Audio: first decoded PCM ... backend=software` 和 `outputBuffersTotal`：是否成功生成音频。
3. `route=streamType=2`、`playing=true`、`playbackHeadFrames`：是否进入并推进所选播放输出。

只有已经得到有效 PCM 且播放位置前进仍无声时，再继续核对车机输出路由和音量。这次修复不宣称解决所有音频策略或高德自身静音设置问题。
