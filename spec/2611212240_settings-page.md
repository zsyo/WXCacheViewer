# 设置页：统一导出目录、缓存管理与关于

## 需求

右上角（排序、视图按钮之后）加一个齿轮入口，进入设置页，分「数据」与「关于」两块：

1. 导出目录：选一个统一目录，导出时在其中按类型新建子目录，取代原来按类型散落到 `Music/`、`Movies/`、`Pictures/`、`Download/` 的做法。
2. 缓存管理：显示应用缓存目录占用，提供一键清理。
3. 关于：应用信息、开源仓库、开发者、开源许可、隐私说明、免责声明。

## 导出目录

用系统文件选择器（`ACTION_OPEN_DOCUMENT_TREE`）选目录，而不是让用户手输路径：

- 用户能看到熟悉的目录树，Android 11 之后也不需要「所有文件访问」权限；
- 选中后调用 `takePersistableUriPermission` 持久化读写授权，重启后仍有效。

但应用是用 root 通道写公共目录的（应用自身在 Android 10+ 写不了 `Download` 以外的位置），root 需要的是绝对路径。所以把 SAF 的 tree documentId 转成路径：

```
primary:Download/WXCacheViewer  ->  /storage/emulated/0/Download/WXCacheViewer
1234-5678:导出                  ->  /storage/1234-5678/导出
```

解析规则（`domain/ExportLocation.resolve`，纯函数、可单测）：

- 只接受 `com.android.externalstorage.documents`（本机存储）；网盘、下载器之类的 provider 没有本地路径，直接提示「只支持本机存储上的文件夹」。
- 拒绝整卷根目录（`primary:`），否则用户一选就是存储卡根，导出的文件全散在根目录。
- 拒绝空段、`.`、`..`、空相对路径，避免拼出越界路径。
- 次级存储卷按 `volumeId` 拼 `/storage/<volumeId>/<相对路径>`。

分类子目录：

| 缓存类型 | 导出子目录 |
| --- | --- |
| 视频号视频、聊天视频 | 视频 |
| 聊天图片、朋友圈、表情 | 图片 |
| 语音（mp3 / 原始 amr） | 语音 |
| 聊天文件 | 文件 |

目录由 root 导出命令里的 `mkdir -p` 顺带创建，不需要应用自己建。没设置过导出目录时，根目录回退到 `Download/WXCacheViewer`，行为与「统一目录」保持一致，不会再回退到按类型分散的老逻辑。

## 缓存管理

统计范围是应用私有 `cacheDir`：`media/`（复制出来的媒体）、`voice_raw/`、`voice_decoded/`、Coil 的磁盘缓存都在这里。

- 大小用 `walkTopDown()` 求和，放在 IO 线程，进设置页时刷新一次。
- 清理就是删 `cacheDir` 下的一级子项，只影响应用自己的临时文件；微信的缓存目录完全不动，删掉的媒体与解码结果下次查看时自动重建。
- 清理中按钮禁用并显示「清理中…」，完成后把剩余大小与释放量回显到 Snackbar。

## 关于

卡片头部是应用名、版本（`PackageManager` 取 `versionName`）与一句话简介，下面依次是：

- 开源仓库：`https://github.com/zsyo/WXCacheViewer`，点击用系统浏览器打开，没有浏览器应用时 Snackbar 提示。
- 开发者、开源许可（SILK / LAME / AndroidX / Compose / Media3 / Coil / 本项目）、隐私说明、免责声明，后三项用对话框展示全文。

## 实现位置

| 文件 | 作用 |
| --- | --- |
| `domain/ExportLocation.kt` | documentId 到绝对路径的解析、分类目录枚举 |
| `data/repository/SettingsRepository.kt` | 导出根目录路径与 SAF URI 的持久化 |
| `viewmodel/SettingsViewModel.kt` | 缓存统计、清理、导出目录读写 |
| `ui/settings/SettingsScreen.kt` | 设置页界面、目录选择器、关于与弹窗 |
| `ui/AppRoot.kt` | 设置页作为覆盖层盖在相册上，返回后保留标签、列表与滚动位置 |
| `ui/gallery/GalleryScreen.kt` | 顶栏新增齿轮按钮 |
| `viewmodel/GalleryViewModel.kt` | 导出目标改为「根目录 + 分类子目录」 |

## 边界

- 用户在设置页清理缓存时，正在播放的语音可能已被删除；Linux 允许删除已打开的文件，当前播放不受影响，下次播放会重新复制并解码。
- 自定义目录如果被卸载（SD 卡拔掉）或授权失效，导出会由 root 命令失败并返回错误提示，不会静默丢文件。
- 设置页是覆盖层，不销毁相册界面，因此返回后多选状态、标签页与滚动位置都还在。

## 验证

- `ExportLocationTest` 5 条：主存储、次级存储、整卷根、非法段、显示名。
- 单测 80 条全绿，`lintDebug` 0 error。
- 真机：设置页可进入、能选目录、导出后文件出现在 `<根目录>/视频`（等分类）下，清理缓存后大小归零。
