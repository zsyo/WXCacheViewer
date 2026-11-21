# 视图切换改为单按钮

## 目标
顶栏右侧原本是两个并排图标按钮（列表 / 网格，选中项高亮），占用宽度且需要用户识别哪个是当前状态。改为单个图标按钮，点击即在两种视图之间切换。

## 方案
- `GalleryScreen` 顶栏 `actions` 只保留一个 `IconButton`，图标与 `contentDescription` 由当前模式推导出的目标模式决定：
  - 当前为两列网格 → 显示列表图标，`contentDescription` 为"列表视图"；
  - 当前为一列列表 → 显示网格图标，`contentDescription` 为"网格视图"。
- 图标语义遵循 Material 动作约定：图标表示"点击后将切换到的模式"，避免歧义。
- 删除已无用的 `ViewModeButton` 组合函数；改由 `onSetGridMode(nextMode)` 复用原有持久化逻辑，`GalleryViewModel.setGridMode` 无需改动。
- 单击图标使用 `onSurfaceVariant` 着色，不再需要选中态高亮。

## 完成标准
`assembleDebug` 与 `testDebugUnitTest` 通过；真机顶栏右侧只有一个视图图标，点击一次在 1 列 / 2 列之间切换，图标随之互换，重启应用后保持上次选择。
