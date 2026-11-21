# 顶栏改造：短 hash、账号切换合并、视图图标

## 目标
顶栏原先直接展示 32 位 hash（换行显示），并且"切换账号"和列数切换都是文字按钮，观感差。改为：

- 左上角显示 8 位短 hash，有备注时备注为主、短 hash 为副；
- 短 hash 与下拉箭头组合成一个可点击区域，点击打开账号切换弹层；仅一个账号时箭头置灰且不可点击；
- 右侧列数切换改为图标按钮（列表 / 网格），选中项用主题色高亮，参照 MX Player 的视图切换形式。

## 方案
- `ui/common/Formatters.kt` 增加 `shortHash(hash)`（`SHORT_HASH_LENGTH = 8`）。
- 新增矢量图标 `res/drawable/ic_view_list.xml`、`ic_view_grid.xml`，不引入图标扩展依赖。
- `GalleryScreen` 顶栏：`AccountTitle`（Column + ArrowDropDown，`clickable(enabled = canSwitchAccount)`）替换原 Text；`ViewModeButton` 替换原 TextButton。
- `GalleryViewModel.toggleGridMode()` 改为 `setGridMode(mode)`，点击已选中模式时不做任何事；`AppRoot` 同步传参 `remark` 与 `onSetGridMode`。
- 缩略图补首帧兜底（`getFrameAtTime(0)` → 1ms → 无参），失败与复制失败写入 `AppLog`，便于定位个别文件只有占位符的问题。

## 完成标准
`assembleDebug` 通过；真机顶栏为单行短 hash + 下拉箭头，右侧两个图标可切换 1/2 列并持久化。
