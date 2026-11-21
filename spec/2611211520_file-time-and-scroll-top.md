# 卡片显示修改时间并在排序后回到顶部

## 目标
1. 列表卡片第二行补充文件修改时间，便于对照"按修改时间排序"的结果。
2. 每次排序结果更新后，列表自动滚动回顶部，避免停留在旧位置的中间条目上。

## 方案
- `ui/common/Formatters.kt` 新增 `formatModifiedAt(epochSeconds, nowMillis)`：Unix 秒转本地时间；当年显示 `MM-dd HH:mm`，跨年显示 `yyyy-MM-dd`；取值 <= 0 返回空串。时间格式用 `SimpleDateFormat`（minSdk 24 下不使用 `java.time`，避免引入 desugaring）。
- `CacheFileTile` 第二行文案改为 `类型 / 大小 [/ 修改时间]`，时间不可用时省略该段，不显示占位。
- `GalleryScreen` 引入 `rememberLazyGridState()` 并传给 `LazyVerticalGrid`；`LaunchedEffect(state.files)` 在文件列表内容变化时调用 `scrollToItem(0)`。切换排序、切换账号、重新加载都会产生新的列表内容，因此统一回到顶部；文件列表为空（加载中）时跳过。

## 完成标准
`assembleDebug` 与 `testDebugUnitTest` 通过（新增 `FormattersTest`：当年格式、跨年格式、不可用时间返回空串、短 hash 截取）；真机卡片显示时间，滚动到中段后切换排序会自动回到第一条。
