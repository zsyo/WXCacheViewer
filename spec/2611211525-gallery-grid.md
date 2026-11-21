# 相册网格与布局切换

## 目标
以相册式网格展示缓存文件，支持一行两个与一行一个切换并持久化。

## 方案要点
- `GalleryViewModel` 加载当前账号文件列表，暴露 `StateFlow`；网格模式从 SharedPreferences 读取并持久化。
- `GalleryScreen` 使用 `LazyVerticalGrid` + `GridCells.Fixed(2/1)`，顶栏提供布局切换按钮与账号切换入口。
- 单账号时切换账号入口可见但禁用。
- 每项显示类型占位、文件名、大小；真实缩略图在下一阶段接入。

## 完成标准
`./gradlew assembleDebug` 通过；杀进程重启后布局模式保持不变。
