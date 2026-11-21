# 视频首帧缩略图与快速切号

## 目标
网格项显示视频首帧预览；顶栏提供账号快速切换，单账号时入口可见但不可用。

## 方案要点
- `ThumbnailLoader` 通过 su 把缓存文件复制到 App 私有 `thumb_tmp/<hash>_<name>.mp4`，`chmod 0644` 后用 `MediaMetadataRetriever` 取首帧，缩放到 320px 宽。
- 内存 `LruCache` 按 `hash/name` 缓存 Bitmap；解码后删除临时文件；切号时清空缓存与临时目录。
- Compose 侧用 `produceState` 在后台加载，随网格项离开组合取消。
- 顶栏“切换账号”始终显示，单账号禁用；弹出对话框列出账号与备注，可就地修改备注并切换。

## 完成标准
`./gradlew assembleDebug` 通过；真机网格显示首帧，切号后缩略图重新加载。
