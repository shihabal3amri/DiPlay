# 博越 L Ultra 实验接入（2026-10-14）

目标手机：用户提供 iOS 27.2。未连接实车或 iPhone，本次不能宣称原生仪表已经使用真实车况。

## 当前交付范围

保留上次博越 L 方控、原厂 DIM、地图副屏适配。移植 xcertplay 的 Ultra 分支
`e021581f1cdcebc495e5a81aae1bef5154848657` 中的 RCS、CAF、OPACK、UI Sync 编解码、配置与能力协商。
2026-10-14 已合并 DiPlay 最新 main `c30d3f0df18dd132739d864a9e054c73b3d57d8c`，
在上游实现上保留 Ultra 定制改动。最初适配基线为 `8352021c0bc2612fff2e51c9a760abf95a5c5c54`。
本次合并不会补齐下面列出的原生仪表协议与渲染缺口。

新增：

- 设置 → 车辆选择博越 L → 高级 → 博越 L 仪表显示 → CarPlay Ultra（实验性），默认关闭，保存后重连。
- Ultra 优先使用 Display 4，只有屏幕有效、ON、公开 PRESENTATION、1920×532 才声明；不强制显示普通地图 URL。
- /info 的 vehicleStateProtocolInfo、uiSyncInfo 与 SETUP 的手机提议交集；未提议的 Ultra 功能不启用。
- type-130 的 CAF Data/Data2、ClusterControl 通道；独立 streamID、加密、收发、定向 TEARDOWN、断连清理。
- UI Sync 的 resetComplete 和命令收包 ACK、重复包和会话序号处理。当前 ACK 实现仅用于收包确认；真实 iOS 的时序及 ACK 语义尚需实测，不意味着布局已经渲染。
- FX11 HIDL 2.0 只读数据源：后台限量读取、状态检查、失败/超时/过期清理，不调用车辆 set。
- CAF 配置、读取、订阅、退订、增量更新和失效通知。所有车辆写入和控制请求拒绝执行。

## 为什么还不是完整原生 Ultra 仪表

[xcertplay 公共分支](https://github.com/shilapi/xcertplay/tree/iAP2-rewrite) 的默认 CAF 配置为 `accessories: []`，
类型目录只有 VehicleMotion 等大类及部分空调 characteristic，没有原生车速、转速、挡位所需的完整属性定义。
同时没有完整的 Apple Local UI 资源接收、脚本执行与多层渲染器。

因此本包默认只能验证 Ultra 协商、控制消息和真实数据采集，**不会猜测 Apple 的 characteristic ID 或发送模拟车况**。
默认没有真实仪表 CAF bindings，所以采集到的数据尚不能驱动 Apple 原生表盘。用户截图中的 SIMULATED DATA 不能用作真实数据已经接通的证据。

还需要与作者成功版本匹配的车辆配置/协议证据，以及本地绘制部分（如果目标布局需要）。
当前源码支持接入已核实的 CAF 配置，但本次没有生成冒充真实车型的配置文件。

## 车辆数据

| 信号 | 读取属性 | 处理 |
|---|---:|---|
| 标准车速 | 291504647 | m/s 转 km/h，使用绝对值；只在实际配置中存在且可读时使用 |
| FX11 仪表车速原始值 | 557874773 | 保留 raw，未确认单位，不当成 km/h |
| 发动机转速 | 557850019 | FX11 映射 raw / 2，超范围不可用 |
| 换挡杆位置 | 557874334 | 0/1/2/3 → P/R/N/D；其他枚举不可用 |
| 标准车外温度 | 291505923 | 摄氏度；不混用未核实的厂商原始温度 |
| 标准剩余续航 | 291504904 | 米转公里；依赖车辆实际暴露 |
| 厂商电量/燃油/续航 | 561024509 / 561024416 / 561024488 | BYTES 编码未确定，不解析为数值 |

参考用户 FX11 Demo 的 Profile/HIDL 实现和原始映射；标准单位核对自
[Android VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds)。
每条有效读取最多保留 2.5 秒。没有数据不等于零车速、P 挡或零油量。
原厂属性映射来源为 Mikhail2092/geely-third-party-software-analysis，CC BY 4.0，保留来源说明。

## CAF 配置接入（研发用途）

文件位置为应用私有目录 `files/ultra/fx11-caf-profile.bplist`；缺失时是明确标注的 negotiation-only 模式，
格式错误时关闭 Ultra 协商，不静默采用错误配置。配置最大 512 KiB。

文件是 binary plist 字典：

- `evidence`：真实配置/协议证据来源。
- `pluginId`、`pluginName`：已确认的插件信息。
- `config`：原始 CAF accessories/services/characteristics 配置树。
- `bindings`：每个 characteristic IID 对应的 `signal`（VehicleSignal 枚举）、`sourceUnit`、`targetUnit`、可选 `scale`/`offset` 或 `enumValues`。
- `unavailableError`、`unsupportedError`：该协议确认的非零错误码。

必须每个 characteristic 恰好有一个绑定、支持 invalid 值、禁止静态 initialValue，禁止车辆写入/control。
来源单位必须匹配本地信号，目标单位必须匹配配置树；不能把 accessory type ID 当 characteristic IID。
测试中的 97/98/99 等编号仅为单元测试夹具，没有打包成实车配置。

## 认证与实车验证

DiPlay 发布版可以不使用外接 MFi 芯片；它仍使用内置软件认证身份。此次保留该实现，无需改成外接 MFi。
公开源码按上游惯例不含运行时身份文件，所以 source-only APK 不是可直接连 iPhone 的发布包。
使用已有合法运行时构建配置的流程见 [BUILD.md](BUILD.md)，环境变量为 `DIPLAY_AUTH_ASSETS_DIR`。
本次未提取认证密钥，也没有改变原有认证实现。

验证顺序：

1. 先用可正常认证的构建确认主屏 CarPlay/音频正常，再启用 Ultra 并重连。
2. 记录 /info、Ultra negotiation requested/enabled、三个 RCS 通道的日志。
3. 高级设置的“仪表连接状态”中查看 FX11 连接、有效字段和 CAF schema 状态。
4. 核对目标 iOS 是否提供 type 111，以及真实仪表是否可见；三屏动画仍由用户按此前流程手动控制。
5. 没有真实 CAF 配置时，不把手机的模拟数值算作接入成功；有配置后核对读取、订阅、变化、失效及断连恢复。
6. 对需要 Local UI 的原生布局，补齐资源与渲染实现后再验收表盘。

本机 adb 没有连接设备，没有安装、写入车辆或切换原车三屏模式。


## HUD Test v8：连接修复与有限恢复（2026-10-15）

本版合入 xcertplay PR #52（2b76c3c3624d50c3a442487beff70d4f5c01e49c）的连接请求修复：
`AirPlay-Receiver-Device-ID` 使用 48 位 MAC 对应的十进制整数；Bonjour TXT 的 `deviceid` 仍保留 MAC 格式。
DiPlay 已有按目标 IP 地址族选择本地绑定地址的逻辑，继续保留双栈发布与作用域处理。

Ultra 交接阶段等待 45 秒仍无画面时，先保留蓝牙初始通道和 Wi-Fi，额外等待 75 秒。
在总计 120 秒的交接截止时间前，无线 iAP2 就绪则正常交接；如果已有渲染画面，保留蓝牙控制作为降级路径；
否则记录 HANDOFF_TIMEOUT 并结束本次连接。不会因单独收到 AirPlay SETUP 就宣称已经连接成功。

实际启用 Ultra 的会话共享同一重试预算，包含启动错误、传输错误和会话结束：最多自动重试 3 次（首次尝试之外）。
短暂建立会话不会清零；首帧出现后同一会话持续 60 秒才重置预算。设置菜单内发生的断线也受此预算约束。
耗尽后停止自动重试，并保留“重试连接”按钮；用户可保存诊断报告、手动重试，或关闭 Ultra 后应用并重连。
普通 CarPlay 保留原有行为。本地 Midnight / Panorama 会禁用会话中的 Ultra，继续使用普通地图副屏。

诊断报告新增：有效 Ultra 状态、控制请求 ID 格式、各次连接与交接状态、是否有首帧、是否有无线 iAP2 通道、
是否进入宽限期、实际重试次数与上限、Ultra 能力协商、RCS 通道接受/拒绝原因、CAF 配置与响应、UI Sync 消息类型和关闭错误类型。
诊断事件在设置菜单打开时仍写入报告，旧连接的收尾日志带连接代次；不保存新的协议原始内容或认证数据。

这是连接兼容性和诊断更新，不代表 Apple 原生 Ultra 仪表已经可用。
默认车辆 CAF 描述仍为空，Local UI 未实现，原生仪表能否完成协商需要真实 iPhone 与车机日志验证。
