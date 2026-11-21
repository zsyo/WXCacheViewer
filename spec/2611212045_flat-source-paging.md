# 扁平源分页加载

## 背景

图片与朋友圈两个标签页按「一级子目录」分页后已经可用。但视频号、聊天视频、表情三个标签页
仍然是一次性列全目录，切过去要等很久。用户反馈希望其它几个标签页也用同样的加载方式。

## 根因

扁平源的老命令是对整个目录做 shell 展开，再逐文件取元数据：

```sh
for f in "$D"/*; do
  [ -f "$f" ] || continue
  printf ... "$(basename "$f")" "$(stat -c %s "$f" ...)" "$(stat -c %Y "$f" ...)"
  head -c 32 "$f" | od -An -v -tx1 | tr -d ' \n'   # 表情与视频号还要探文件头
  ...
done
```

每个文件 3~5 次进程创建（`basename`、两次 `stat`、`head`、`od`、`tr`）。表情目录里
`_gif`/`_cover`/`_thumb` 加起来几千个文件，就是上万次 fork，切个标签页要十几秒。

## 改动

### 扁平源按偏移切片

新增 `WxPaths.listSourcePageCommand(hash, source, offset, limit)`：

```sh
D='<dir>'
[ -d "$D" ] || exit 0
i=0
ls -1r "$D" 2>/dev/null | while IFS= read -r n; do
  case "$n" in *.mp4) ;; *) continue ;; esac
  f="$D/$n"
  [ -f "$f" ] || continue
  i=$((i+1))
  if [ "$i" -le 120 ]; then continue; fi
  if [ "$i" -gt 180 ]; then break; fi
  printf ... # 只对本页这 60 个文件取 size / mtime / 文件头
done
```

关键在于「跳过哪些」这一步是纯 shell 内建（`ls` 一次 + 循环计数），不产生子进程；
只有进入页内的这几十个文件才执行 `stat`。于是首屏成本与目录总文件数脱钩。

几个细节：

- `ls -1r` 倒序：聊天视频文件名前缀就是时间戳（`2609251653228091.mp4`），倒序即「最新在前」，
  与界面默认排序一致。md5 命名的源顺序无语义，但切片本身不会重复或遗漏。
- `case "$n" in <globs>)` 直接复用 `CacheSource.globs`，不需要把 Kotlin 正则转成 ERE。
- 干扰文件（`readme.txt`）与子目录由 `case` 与 `[ -f ]` 双重过滤，且不计入页码。
- 「还有没有下一页」用「本页是否取满 `limit` 条」判断；恰好整除时会多一次空请求，代价可忽略。

### 统一的游标

`PageCursor(sourceIndex, position)`：`position` 对递归源是「下一个一级子目录序号」，
对扁平源是「已取过的文件条数」。`loadPage` 里两类源走同一套推进逻辑：

- 取到内容 → 返回该页与下一个游标；
- 空页 → 继续往后推进（空目录、超出末尾），最多跳 12 次；
- 当前源取完 → 游标自动移到下一个源，所以「图片」标签页会先翻完聊天图片再翻朋友圈。

### 顺带修掉的一个 bug

新命令里尺寸表达式最初漏了 `$(...)` 包裹，`printf` 会把 `stat -c %s "$f" || wc -c < "$f"`
这串字面量当参数打进输出。已修正，并加了断言防止回归。

## 本机实测

在 `video` 目录下放 5 个 `*.mp4`、1 个同名 `jpg`、1 个 `readme.txt` 与 1 个子目录，
按 `offset=0 limit=3` 与 `offset=3 limit=3` 两页执行导出的命令：

```
# 第一页
260925165322805.mp4  8  1795264904  -  260925165322805.mp4  0
260925165322804.mp4  8  1795264904  -  260925165322804.mp4  0
260925165322803.mp4  8  1795264904  -  260925165322803.mp4  0
# 第二页
260925165322802.mp4  8  1795264904  -  260925165322802.mp4  0
260925165322801.mp4  8  1795264904  -  260925165322801.mp4  1
```

5 个文件分两页不重不漏；`readme.txt` 与子目录被过滤；最后一行 `hasThumbnail=1`
说明同名 jpg 探测正确。

## 验证

- 新增测试：`flatSourcePagesByOffset`（满页产生游标、不满页到底）、`flatSourcePageUsesSourcePageSize`、
  `flatSourcePageCommandSlicesByOffset`、`flatSourcePageCommandKeepsThumbnailProbe`、
  `flatSourcePageRejectsBadArguments`。
- 原有断言随行为调整：页内现在按修改时间倒序，`parsesLinesAndIgnoresInvalidNames` 改为按大小断言而非下标。
- `testDebugUnitTest` 57 个用例全绿，`lintDebug`、`assembleDebug` 通过；
  确认 `ls -1r` 已进入 `classes8.dex`。

## 范围外

- 跨页的全局排序。分页后每页单独排序，页与页之间按 shell 输出顺序拼接。做全局排序需要先扫全目录，
  与「首屏不等全量扫描」直接冲突，暂不处理。
- 表情与视频号的 `probeHeader` 仍是每个文件 `head | od | tr`。本页只有几十个文件，
  开销可接受；若以后要再压，可以改成 `od -N 32` 省掉一次 `head`。
