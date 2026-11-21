# 聊天文件（文件标签页）

## 背景

前四个标签页（视频号、聊天视频、表情、图片）已经能分页浏览。用户希望再补上聊天里收发的
文档类文件，也就是微信的 `attachment` 目录。这一版只做「能看、能导出、能打开」，不扩展到语音。

## 设备实测

在真机上列了 `MicroMsg/<hash>/attachment`，内容形如：

```
项目资料 归档.zip         4158664
团队协作流程说明.pdf      1483595
example_preview.webp      88184
da_1700000000000          5778613
```

（文件名与体积均为示例值，不是真机上的实际数据。）

几个结论：

- 目录是**扁平**的，没有子目录，文件名带原始扩展名，中文与空格都合法。
- 少量文件没有扩展名（形如 `da_<13 位毫秒时间戳>`），内容是微信自己的数据，
  识别不出类型也打不开。
- 外部存储的 `MicroMsg/Download`、`networkfiles`、`openim` 在这台设备上是空的，
  聊天文件确实只在内部 `attachment` 里，因此只接这一个目录。
- 同一个账号在外部存储还有一个不同 hash 的目录（`image`、`openim` 等），与本次无关。

## 设计

### 缓存源

`SourceRegistry.CHAT_FILE`：`MicroMsg/<hash>/attachment`，扁平源，`globs = ["*"]`，
`pageSize = 40`，`allowDelete = false`，`preserveFileName = true`。

文件名正则刻意放宽到「除了路径分隔符和控制字符都能用」：

```kotlin
Regex("^[^/\\\\\\u0000-\\u001F]{1,192}$")
```

中文、空格、括号、点号都是正常文件名的一部分，收紧反而会把真实文件挡在外面。
安全性由既有的段级校验兜底（禁绝对路径、`..`、反斜杠、空段、超长）。

**不提供删除**：`attachment` 就是聊天记录里的那个文件，删掉之后聊天里的文件也打不开了，
不像视频号缓存那样删了还能重新缓存。工具条因此不显示删除按钮，命令层 `require` 兜底。

### 类型识别

聊天文件**只看扩展名**，不探测文件头（`probeHeader = false`）。原因是 docx/xlsx/pptx
与普通 zip 的魔数完全相同（都是 `50 4B 03 04`），按魔数识别会把 Office 文档全部认成压缩包。
无扩展名的文件归为 `UNKNOWN`，界面显示「文件」。

`FileType` 因此扩了一组文档类型：`ZIP / PDF / DOC / DOCX / XLS / XLSX / PPT / PPTX / TXT / APK / AUDIO`，
并补上各自的 MIME 与扩展名。新增 `domain/ChatFileTypes` 负责「扩展名 → 类型」与列表徽标文字。

### 分页与命令

复用扁平源的分页命令（`listSourcePageCommand`），一页 40 个：

```sh
D='<attachment>'
ls -1r "$D" | while IFS= read -r n; do
  case "$n" in *) ;; *) continue ;; esac
  f="$D/$n"; [ -f "$f" ] || continue
  i=$((i+1)); [ "$i" -le 0 ] && continue; [ "$i" -gt 40 ] && break
  printf '%s\t%s\t%s\t-\t%s\t0\n' "$n" "$(stat -c %s ...)" "$(stat -c %Y ...)" "$n"
done
```

`read` 用单变量读取整行，带空格的文件名不会被拆开；`case` 的 `*` 只是复用源定义的通配符，
不做类型过滤。本机构造了含中文、空格、无扩展名、子目录与 `.nomedia` 的目录，按两页执行：
每行都是整齐 6 列，子目录与隐藏文件被过滤，两页不重不漏。

### 界面

文件页固定**单列列表**（`SourceTab.listOnly`），顶栏的网格/列表切换按钮在该页隐藏：

```
[PDF ]  团队协作流程说明.pdf
        1.4 MB / 10-19 19:01
[ZIP ]  项目资料 归档.zip
        3.9 MB / 10-26 19:51
```

左侧 44dp 圆角徽标显示扩展名（超过 4 个字符时退回「文件」/「音频」等类型名），右侧两行文字。

### 打开与导出

- 图片类（JPEG/PNG/GIF/WEBP）点开走已有的全屏预览；MP4 走内置播放器；
  其余类型交给系统「打开方式」。
- 打开前先用 `MediaPreparer` 把文件复制到 `cache/media/chat_file/`，再经
  `FileProvider`（`${applicationId}.fileprovider`）以 `content://` 分享，避免 Android 7+
  的 `FileUriExposedException`。MIME 优先用系统的 `MimeTypeMap` 按扩展名取，系统不认识才回退到缓存类型。
- 复制中该行徽标位置显示环形进度，失败时用 Snackbar 提示。同时只允许打开一个文件。
- `MediaPreparer` 的落盘扩展名改为**优先用原文件名的扩展名**：否则 `docx` 会被存成 `.bin`，
  系统找不到对应的应用。
- 导出到 `Download/WXCacheViewer/`，**保留原文件名**（`CacheSource.preserveFileName`）；
  目标目录已有同名文件时依次加 `(1)`、`(2)` 后缀，不覆盖用户之前导出的。

## 验证

- `ChatFileTypesTest`：常见扩展名映射、无扩展名/rar 归为未知、徽标截断。
- `SourceRegistryTest`：中文与空格文件名可通过、含 `/` 与制表符的文件名被拒、文件页只读。
- `WxPathsTest`：分页命令指向 `attachment`、通配符覆盖全部文件名、不探测文件头。
- `CacheRepositoryTest`：按扩展名解析出 DOCX/PDF/ZIP/UNKNOWN、带空格文件名的 key 正确；
  导出保留原名并在重名时改名。
- `testDebugUnitTest` 64 个用例全绿，`lintDebug`、`assembleDebug` 通过；
  合并清单中确认 `top.aico.wxcacheviewer.fileprovider` 与 `res/xml/file_paths.xml` 已打入包内。

## 范围外

- 语音（`voice2` 实际是 SILK v3）仍未支持，需要先解码成 PCM 才能播放。
- 不带扩展名的文件（如 `da_1700000000000`）只列出、可导出，不尝试猜测与打开。
- 聊天文件与消息、发送人的对应关系需要读加密的 `EnMicroMsg.db`，不在本次范围内。
