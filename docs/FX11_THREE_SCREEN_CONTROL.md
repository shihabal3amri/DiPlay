# FX11 三屏动画与仪表切换

`Fx11ThreeScreenController` 只封装原厂仪表/投影模式开关，不写其他车辆功能。接口来自本车 FX11 demo 的 `EcarxClient` 和 2026-10-11 实车记录：

- ECARX function：`SETTING_FUNC_THREE_SCREEN_ANIMATION = 540148992 / 0x20320500`。
- global zone：`Integer.MIN_VALUE`。这不是底层 VHAL 属性的 area。
- `getFunctionValue(function, zone)` 读取，`setFunctionValue(function, zone, value)` 写入，值仅为 `0` 和 `1`。
- 本车记录显示普通应用读到 `active`、zone `MIN_VALUE`、支持值 `[0,1]`。用户手动开启后 Display 4 的 Presentation 在仪表上可见；手动关闭后原厂仪表恢复。

不能将 Android `Presentation.show()`、ECARX setter 返回 true 或开关回读成功等同于实屏显示成功。未准备好图像时开启三屏曾导致原厂仪表隐藏后黑屏。单纯 dismiss 本地窗口不会关闭三屏。

## 接线

```kotlin
val controller = Fx11ThreeScreenController()
controller.start(applicationContext) // 不连接、不读取、不写车。
// 仅由明确的用户 MODE 短按触发；需先准备有效 Display 4 和已绘制窗口。
controller.toggle(canShowDashboard = presentationReady) { result ->
    // 默认主线程。success 仅代表 OEM 开关回读匹配，不代表物理屏验证。
    // result: before / target / after / writeAttempted / message。
}
// Display 失效或窗口撤下时，立即使尚未发送的开启失效；不触发任何 OEM 调用。
controller.updateDashboardReady(false)
// 用户在设置/通知明确选择关闭仪表时，固定恢复为0；已经为0时不会反向开启。
controller.restoreOem { result ->
    // 仅确认成功后再将style设为OFF并退出Service；失败保留面板并显示诊断。
}
controller.close() // 取消尚未提交的操作、释放资源；不会自动写车或恢复模式。
```

`canShowDashboard=false` 只阻止 `0→1`，仍允许 `1→0`，保证窗口不可用时也可尝试回到原厂仪表。模式回到 0 后继续保留控制器、Service 和 MODE 键监听；不要通过 `style=OFF` 实现每次 MODE 切换。可把已绘制的 Presentation 保持在原厂图层下方，供下一次 MODE 开启使用。

每次明确按键操作重新连接并读取状态。写前要求 `active`、支持的 global zone、同时支持 0/1、当前值为 0/1；未知状态和缺少 SDK 不写。一次操作最多一次写入，最多 8 次回读、间隔 250ms。快速连按在前一个操作完成前被拒绝，不排队反转状态。

12 秒操作期限到达后终止后续写入/回读，报告失败且不自动重发。已进入 Binder 的写入无法撤回，超时后的实际状态可能不确定；下一次明确按键必须重新读取。阻塞中的 OEM 调用不会被替换线程继续发送第二条命令。关闭不会假装恢复原厂；Service 的退出流程必须单独处理明确用户授权的恢复操作及恢复失败提示。

## 验证边界

专属单元测试覆盖：启动/关闭无写、按键单写、关闭不受窗口就绪限制、未知状态/权限/能力拒绝、拒写不重发、有限回读、忙时去重、取消、读取超时后不发迟到写入。新集成尚需在本车验证实体 MODE 回调、SDK 可见性及物理仪表切换。

“MODE 旁边的切页键”不能仅凭 SDK 名称猜测。已知 `MODE=200110`；`MENU=200082` 和 `MULTI_FUNCTION=200600` 只有常量证据，没有该实体键的实车映射证据。应由用户确认/按键学习配置，保持辅助驾驶方向键和跟车距离滚轮的原车行为。

## 方控学习接线

`GeelyWheelKeys.learnDashboardPageKey(context, onLearned)` 返回取消函数，最长监听 45 秒。`onLearned(code)` 在主线程至多返回一个短按候选；并不保存键码。设置弹窗在用户确认后调用 `NativeDashboardSettings.setPageKey(context, code)`，再 `GeelyWheelKeys.refresh()`，并在确认、取消、弹窗/页面销毁时调用取消函数。超时也不会自动保存。

学习仅订阅提取 SDK 中明确的 13 个菜单/媒体常量：MENU、MULTI_FUNCTION、HOME、BACK、SETTINGS、MEDIA、NAVI、SRC、RADIO、WECHAT、播放/暂停、上一首、下一首。没有使用“空数组代表所有键”的假设，没有调用 OneOS 或 ECARX 按键拦截 API。该有限候选范围之外的实体键不会被猜测学习；手动输入也应使用 `DashboardWheelKeyPolicy.isPageKeyAllowed` 校验，未知键需补充设备证据后支持。

学习期间本应用不响应媒体、切页和 MODE 动作；原车自身仍可能处理按键。正常监听只包含 MODE、已确认的页面键，以及现有 CarPlay 媒体键。MODE 只请求三屏开关；页面键仅切换页面。两个仪表键各自防抖 350ms，并丢弃旧 Binder 连接和旧监听器的回调；CarPlay 断开不释放离线仪表持有的按键监听。


## V12: 本车 MODE 校准与未知 AUTO 图标 (2026-10-16)

- 本车单独短按 MODE，新增完整 `200400` down/up/short 回调；此前写死的 SDK MODE `200110` 不适配本车。拉取的 `com.geely.inputservice` 1.0.20260509G(5d) 上游数组含200400、不含200110。SDK对200400的名称仍为WECHAT，物理对应依据是本次隔离按压，不能推广为所有吉利车辆的固定映射。
- 新增安装级 `native_dashboard.mode_key`，未设置仍200110；本车部署保存已确认200400。注册、分流及诊断统一使用保存值，翻页键与MODE禁止冲突，未确认的RESET继续未绑定。旧冲突配置读作未绑定；学习忽略当前MODE，避免误存成翻页键。
- 三套样式中，AUTO状态未知时完全不绘制该灯图标与A字；已知开/关保持绿/暗灰。保留其它灯、三套仪表、普通CarPlay及V10油量/续航/胎压/温度读取。ACC设定速度及车道居中未确认项仍未知。
- RESET在前一轮14候选监听内，两次隔离短按未产生新回调；这只是该外发路径的阴性结果，不能证明车辆不发送RESET，也未据此猜码绑定。后续范围限定为本车InputService固定31唯一码的临时被动监听，以及HW_KEY_INPUT单属性订阅；均不拦截、不写车辆属性。
- 原始静态与运行时证据保存于 `outputs/diplay-native-dashboard-v11/device/inputservice-analysis/` 和 `runtime-reset-isolated.txt`。本节先记录代码与证据边界，实车安装/最终读回应以V12交付目录installation与device记录为准。
