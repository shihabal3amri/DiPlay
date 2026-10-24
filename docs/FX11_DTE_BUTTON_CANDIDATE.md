# FX11 续航显示类型按键：候选翻页通道

## 已知证据

用户的 `fx11-vhal-demo-v0.5/app/src/main/java/dev/example/fx11vhal/Profile.java:87`
把示例属性 `10059` 命名为“续航显示类型按键”。同项目
`app/src/main/assets/vhal_fx11_signal_mapping.json:811` 映射为：

- 真正 VHAL ID：`561024641`（`padtetypebutton`），BYTES 类型。
- `10059` 不是 VHAL ID，也不是 OneOS 或 Android 按键码。
- 标签不能证明它是 RESET；也没有证明 0/1 就是松开/按下。
- 2026-10-17 ADB 未发现车机，用户明确先完成图片和代码。没有新增实车观测证据。

## 测试实现

原生仪表服务开启后，使用与示例一致的 HIDL 2.0 通道订阅这一个属性（FROM_CAR），
无需连接 iPhone。有可读配置时尊重 area、changeMode 和采样率；目录缺失时仅尝试这个
明确映射的属性。基线 getter 只初始化，不触发翻页。没有 setter、按键注入或拦截操作。
平台类仅为 compileOnly 签名，不进入 APK；普通设备缺少 HIDL 时此通道失败关闭，普通
CarPlay 和现有 MODE / OneOS 方控不依赖它。

由于事件语义未确认，本测试版只把以下形状视为**候选短按**：

1. status=AVAILABLE、area=0、16 字节 PA_IntBase 且 availability=1。
2. 初始读数不触发；事件时间戳必须有序、非未来且不超过 2 秒。
3. 明确观察到 `0 → 1 → 0` 完整脉冲，持续 40～1200 ms，在释放时尝试翻页。
4. 重复值、重复/乱序回调、350 ms 内重复脉冲、长按、非 0/1 枚举均不翻页。
5. 如果实际信号是计数器、锁存模式或不同枚举，只保留日志，等实车证据再改解析。

翻页仍使用共用的 `NativeDashboardSettings.cycle()`：Midnight/Replica 为音乐→导航→胎压；
Panorama 保留地图，侧卡在音乐/胎压之间切换。已保存的 OneOS 翻页键优先；方控关闭、
按键学习中、仪表关闭或服务销毁后不响应候选脉冲。MODE 校准键和三屏动画开关不变。

连接在后台执行，3 秒超时失效；没有无限自动重连。服务销毁、超时或 VHAL 死亡会使
已排队的翻页回调失效，并在后台退订。诊断保留最近 64 条；常规 raw logcat 最多 256 条。

## 下次实车核对

日志标签：`DiPlay-DteButton`。也可在应用诊断报告或以下只读命令中查看：

```sh
adb shell dumpsys activity service com.shihab.diplay.hudtest/com.shilapi.xcertplay.NativeDashboardService
adb logcat -v threadtime -s DiPlay-DteButton:I
```

确认日志中有 subscribed 后，停车状态下只短按 RESET 两次并记录时间；核对原始 PA 值、
时间戳和翻页是否一一对应，再与 MODE 单独按压区分。没有关联证据前不称“RESET 已修复”。

## 界面范围

本轮另外提供两张水温效果图：速度圆盘内的燃油百分比替换为水温图标及示例 90°C。
这些效果图仍待确认，未把此前 v2～v6 的整套视觉调整混入按键测试 APK；安装包延续
v13 三套仪表画面。本轮代码只增加候选事件通道和诊断。
