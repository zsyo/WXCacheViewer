# 顶部下拉刷新

## 目标
相册列表支持从顶部下拉刷新缓存文件，并带下拉指示动画；刷新过程保留原有列表内容，不清空界面。

## 方案
- 复用 Material3 自带的 `PullToRefreshBox`（Compose BOM 2024.12.01 已提供），不引入 Accompanist 等额外依赖。默认 `Indicator` 即包含下拉箭头旋转、容器展开、刷新中不确定进度三段的完整动画。
- `GalleryUiState` 增加 `refreshing`；`GalleryViewModel.refresh(hash)` 与 `load` 的区别：
  - 不设置 `loading`，列表保持可见；
  - 通过 `refreshing` 驱动指示器；
  - 重新执行同一条列表命令后按当前排序重排，`LaunchedEffect(state.files)` 会把列表带回顶部；
  - 已有刷新在跑时直接忽略重复触发。
- `GalleryScreen` 把内容区包进 `PullToRefreshBox(isRefreshing, onRefresh)`，`padding` 移到外层 modifier，网格只保留 `fillMaxSize()`；新增 `onRefresh` 参数，由 `AppRoot` 传入 `refresh(selectedAccountHash)`。
- 刷新失败不再整屏切到错误页：`when` 分支改为 `files.isEmpty() && error != null` 才显示 `ErrorView`；有内容时通过 `SnackbarHost` 弹出错误提示，列表继续可用。

## 完成标准
`assembleDebug`、`testDebugUnitTest`、`lintDebug` 通过；真机在列表顶部下拉出现指示器动画，松手后重新读取目录，刷新期间原列表不消失，刷新失败保留列表并弹出提示。
