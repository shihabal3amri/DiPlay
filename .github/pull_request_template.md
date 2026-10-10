## 变更概述 / Pull Request Summary

<!-- 简要描述本次 PR 解决的问题或新增的特性 / Briefly describe the changes in this PR -->

---

## 目标 DiLink 硬件平台代际 / Target DiLink Platform

请勾选本次改动所针对或验证的 DiLink 硬件平台（可多选）：
Please select the target DiLink platform(s) for this change:

- [ ] **DiLink 150** (31.x / 34.x，Android 13+，8295 / 8+ Gen1 / BYD 9000，DiLink 5.1 / 6.0)
- [ ] **DiLink 100** (23.x，Android 12，骁龙 778G / 782G，DiLink 5.0)
- [ ] **DiLink 50 · 4.0** (16.x / 17.x / 21.x，Android 10，骁龙 690 / 665，DiLink 4.0)
- [ ] **DiLink 50 · 3.0** (13.x / 15.x / 18.x，Android 9 / 10，骁龙 665 / 6125，DiLink 3.0)
- [ ] **DiLink 20** (2.x / 4.x / 5.x / 8.x / 26.x，MTK P35 / 骁龙 625，DiLink 2.0 / 2.1)
- [ ] **通用功能 / Universal** (所有 DiLink 代际及标准 Android 车机均可使用)

---

## 实车测试信息 / Vehicle Test Information

- **测试车型 / Vehicle Model**：<!-- 例如：2023 款汉 EV / 2022 款唐 DM-i / 元 PLUS / 海豹 / 腾势 D9 等 -->
- **车机控制器版本 / Controller Version**：<!-- 例如：23.1.2.xxxxxx / 13.1.0.xxxxxx / 31.2.0.xxxxxx -->
- **Android 系统版本 / Android Version**：<!-- 例如：Android 10 / 12 / 13 -->
- **测试环境 / Test Environment**：
  - [ ] 实车验证通过 / Verified on physical vehicle
  - [ ] 台架测试 / Bench test
  - [ ] 仅通过单元测试与模拟器 / Unit tests & emulator only

---

## 设置分类与信息架构自查 / Settings IA Checklist

若本次变动涉及设置界面（Settings）：
If this change affects the Settings UI:

- [ ] 设置项遵循了 `AGENTS.md` 中的目标分类原则（Connection / Display / Audio / About / DiLink / Advanced）
- [ ] 针对特定代际的专有特性已在 `DiPlayActivity` 中做好版本适配，避免对不兼容机型产生干扰或报错
- [ ] 新增字符串已同步更新至全部 7 种语言（`values`, `values-zh-rCN`, `values-zh-rTW`, `values-es`, `values-ru`, `values-uk`, `values-ar`）
- [ ] 若改动需重新连接 CarPlay，文案中已明确说明并调用了 `markReconnectNeeded()`

---

## 本地 CI 验证 / Local CI Verification

提交前已在本地通过官方完整 CI 命令测试：
The full CI suite passed locally before submitting:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```
- [ ] 单元测试通过 / Unit tests passed
- [ ] Lint 检查通过 / Lint passed
- [ ] 调试包编译打包成功 / assembleDebug passed
