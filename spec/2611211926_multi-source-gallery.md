# 多类型缓存浏览：聊天视频、表情、图片

## 目标
保留视频号视频功能不变，新增三个标签页，覆盖微信其它明文缓存目录：聊天视频、表情、聊天图片与朋友圈。

## 设备实测依据
- `MicroMsg/<hash>/video/`：`<时间戳序号>.mp4` 为标准 MP4（`ftyp isom`），同目录有同名 `.jpg` 首帧；另有 36 字节的 `*.jpg⌖` 失效占位。
- `MicroMsg/<hash>/emoji/`：裸 `<md5>` 为加密数据（头部随机，无法解码）；`<md5>_gif` 为明文 GIF，`<md5>_cover`、`<md5>_thumb` 为明文 PNG。
- `MicroMsg/<hash>/image2/<2hex>/<2hex>/th_<32hex>`：明文 JPEG 缩略图，只有缩略图。
- `cache/<hash>/sns/<1hex>/<1hex>/`：`snst_*` 为 PNG、`snsu_*` 为 JPEG，目录里还有 `.nomedia` 需过滤。
- `voice2/*.amr` 实际是 SILK v3（`02 #!SILK_V3`），本次不做。

## 方案要点
- 新增 `domain/source/CacheSource`：一条源声明根目录（cache/MicroMsg）、相对目录、是否递归、文件名白名单、导出策略、是否允许删除。
- `SourceRegistry` 注册 5 个源；`SourceTab` 把它们归到 4 个标签页，「图片」合并聊天图片与朋友圈两个只读源。
- 列表命令输出扩展为六字段：文件名、大小、mtime、文件头（`-` 表示不探测）、相对路径、是否有同名 jpg 首帧；旧的四字段解析向后兼容。
- 递归源用 `find -maxdepth N -name ... | head -n 4000 | while read` 配合 `stat`，不使用 Android toybox 不支持的 `-printf`；Kotlin 侧按修改时间倒序保留 1500 条并提示截断。
- 安全：源正则 + 段级校验（禁绝对路径、`..`、反斜杠、超深目录）+ 拼接后前缀断言 + 文件名与相对路径末段一致性校验；只读源禁止生成删除命令。
- `CacheFile` 增加 `relativePath`、`sourceId`、`hasThumbnail`，以 `key = "源:相对路径"` 作为列表 key、选中集合与缓存键，避免跨目录同名冲突。
- 视频缩略图：聊天视频优先复制同名 jpg 解码，失败再回退视频首帧；其它视频仍走首帧。
- 图片加载：新增 `MediaPreparer` 用 root 把图片复制到私有目录（并发 3、同 key 去重、保留 300 个），再用 Coil 加载；网格用不带动图解码器的实例只显首帧，全屏预览用带 GIF/ImageDecoder 的实例播动图。
- 新增全屏图片预览页：左右翻页、动图播放、底部信息、顶栏保存。
- 导出：视频进 `Movies/WXCacheViewer`，图片与表情进 `Pictures/WXCacheViewer`，媒体库扫描按每个文件各自的 MIME 上报。

## 验证
- 单测：源白名单与遍历拒绝、六字段解析、跨目录同名丢弃、递归列表按时间倒序、只读源禁止删除、命令不含 `-printf`。
- `assembleDebug`、`testDebugUnitTest`、`lintDebug`、`assembleRelease` 通过。
- 真机：四个标签页切换不串状态；聊天视频有同名 jpg 时用 jpg 作缩略图；表情页只出现 `_gif/_cover/_thumb`；图片页能翻看大图；导出后相册可见；只读源看不到删除按钮。

## 不在本次范围
- 静态表情解密（密钥在加密库中）。
- 语音 SILK 解码、聊天文件（attachment）、收藏（favorite）。
