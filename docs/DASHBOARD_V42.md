# v42 经典仪表白天配色

## 使用
设置 → 高级 → 博越 L 仪表 → 仪表样式选择「经典仪表」→「经典仪表配色」→「白天」。夜间仍为默认值，两个配色都保留。音乐／导航／胎压页共享配色并保持原有几何布局。

## 实现
- 浅灰绿色底、银灰／淡绿色金属刻度环、深色数字、细深绿色活动内圈；两圆盘仍为 1920×532 布局。
- 导航卡、顶部灯光／辅助驾驶栏、底部时间／续航／挡位栏同步变为浅色。保留之前的 16px 左右内边距及单侧圆角。
- 音乐封面原图保持颜色；封面模糊层仅使用浅色遮罩，避免深色文字压在暗图上。中间音乐背景向浅灰底渐隐。
- 白天配色为本地经典仪表属性，单独保存。切换后由现有 250ms UI 观察循环应用，不改变视频 Surface、内容页、地图窗口、主屏 CarPlay 或 iPhone 地图的日夜设置。
- 当地图视频来自手机时，不对视频做反色、调色或像素替换；白天地图由手机端决定。预览中的道路为测试图，不随 APK 下发。
- 缺失数据继续显示未知，导航未开始时不显示导航卡；另外两套仪表与原厂叠加模式不受此配色影响。

## 代码
NativeDashboardSettings.kt: 配色持久化；DiPlayActivity.kt: 设置入口及 8 种语言；ReplicaDashboardPalette.kt: 白天颜色表；NativeDashboardView.kt / ReplicaMusicBackdrop.kt: 实际绘制。测试覆盖设置保存、运行中切换、视频与几何保持、其他样式不变、状态颜色对比度、三页与缺失数据渲染。

完整单元测试、lint、构建、签名和本地认证校验结果见 verification.json。实机未连接，本次交付 APK 与代码渲染预览。

视觉参考：用户提供的表盘照片及 Apple CarPlay Ultra 官方示例：
https://www.apple.com/newsroom/2025/05/carplay-ultra-the-next-generation-of-carplay-begins-rolling-out-today/
本次实现的是 DiPlay 本地仪表样式，不改变 Ultra 协议连接能力。
