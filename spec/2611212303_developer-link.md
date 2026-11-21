# 关于页开发者一行可跳转主页

## 目标
设置 → 关于里的「开发者」原本只是静态文字，看不出是个人主页入口；改成可点击，用系统浏览器打开 `https://github.com/zsyo`，与「开源仓库」一行行为一致。

## 方案要点
- 常量 `DEVELOPER_URL` 与现有的 `REPOSITORY_URL` 并列放在 `SettingsScreen.kt` 顶部，地址只在这一处维护。
- 抽出 `openLink: (String) -> Unit`：`startActivity(ACTION_VIEW)` 失败（设备上没有浏览器）时用 snackbar 提示，供仓库与开发者两行共用。
- `AboutRow` 已经支持 `onClick`，传入后自动出现右侧箭头，不需要新增控件。
- 提示文案的 key 由 `settings_about_repo_failed` 改名为 `settings_link_failed`，因为现在两处共用，原名会误导。

## 完成标准
- 「开发者」一行显示 `zsyo` 与右侧箭头，点击跳到 `https://github.com/zsyo`。
- 「开源仓库」一行行为不变，仍跳 `https://github.com/zsyo/WXCacheViewer`。
- `assembleDebug`、`lintDebug` 与既有单测通过。
