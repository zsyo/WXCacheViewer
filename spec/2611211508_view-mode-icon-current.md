# 视图切换按钮图标表示当前模式

## 目标
上一版单按钮的图标表示"点击后将切换到的模式"，实际使用时容易误读为"当前状态"。改为图标始终表示当前模式，点击仍然在 1 列 / 2 列之间切换。

## 方案
- `GalleryScreen` 顶栏 `actions` 中，图标与 `contentDescription` 取自 `state.gridMode` 本身：
  - 当前为一列列表 → 显示列表图标，`contentDescription` 为"列表视图"；
  - 当前为两列网格 → 显示网格图标，`contentDescription` 为"网格视图"。
- 点击目标模式单独推导（`nextMode`），仍调用 `onSetGridMode(nextMode)`，持久化逻辑不变。
- 其余布局、配色、无选中态高亮的处理保持一致。

## 完成标准
`assembleDebug` 与 `testDebugUnitTest` 通过；真机顶栏图标在网格视图下显示网格、列表视图下显示列表，点击一次切换视图且图标同步互换。
