# 账号发现与首次选择页

## 目标
首次启动时列出设备上所有微信账号 hash，允许添加中文备注并选择账号；只发现一个账号时仍显示选择页。

## 方案要点
- `CacheRepository.discoverAccounts()` 通过 su 枚举 `cache` 与 `files/identity_records` 下的 32 位 hash 并集，再批量检测 `finder/video` 是否存在；有缓存的账号排在前面。
- `SettingsRepository` 用 SharedPreferences 保存选中账号、每个 hash 的备注、网格模式。
- `AccountViewModel` 检查 root 状态并暴露 `StateFlow`；root 不可用、读取失败、无账号均有明确错误态与重试。
- `AccountSelectScreen` 提供 hash 展示、备注编辑、选择按钮；单账号显示提示文案。
- 快速切换入口在后续阶段接入画廊顶栏，当前用临时占位页承接已选择状态。

## 完成标准
`./gradlew assembleDebug` 通过；真机上能看到实测 hash、备注重启保留。
