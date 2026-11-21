# 图片缓存改为分页扫描

## 现象

「图片」标签页一进去就整页报错。先是 `read interrupted by close() on another thread`，
改成批量 stat 之后变成「读取超时，该目录文件过多」，始终一列都出不来。

## 现场取证（设备日志 files/logs/app.log）

同一次进入「图片」页尝试了两次，两次都正好在 30 秒超时：

```
19:35:50 [shell] run[su -mm -c ... image2 ... find | head -n 4000 | while read; do stat ...]
19:36:20 [shell] timeout after 30000ms
19:37:52 [shell] timeout after 30000ms
```

改成批量 `stat` 之后：

```
20:16:39 [shell] run[... find | head -n 4000 | tr '\n' '\0' | xargs -0 -n 200 stat -c '%s %Y %n' ...]
20:17:09 [shell] timeout after 30000ms, kept=444629B
```

说明不是内存问题（stdout 上限才 4000 行，约 440KB），也不是进程被杀，而是
**目录遍历本身就慢**：`MicroMsg/<hash>/image2` 是一级 256 个、二级上万个目录的三层结构，
一次 `find` 要读两万多个目录，在这台设备上要几十秒才能走完。

## 三条修复

### 1. 扫描也分页：一页只扫一个一级子目录

不再「一次全量 find」，改成两步：

```
# 第一步：列一级子目录，附带修改时间（一次 stat 处理几百个目录，很快）
stat -c '%Y %n' "$D"/*/

# 第二步：一页只扫其中一个子目录
find "$P" -maxdepth 3 -type f \( -name 'th_*' \) | head -n 400 \
  | tr '\n' '\0' | xargs -0 -n 200 stat -c '%s %Y %n' \
  | while IFS= read -r sz mt path; do
      rel=${path#"$D"/}
      printf '%s\t%s\t%s\t-\t%s\t0\n' "${rel##*/}" "$sz" "$mt" "$rel"
    done
```

一个子目录几十到一百多个文件，几十毫秒就能出结果；整棵树被拆成几百次这样的调用，
由界面滚到底部时逐页触发。一级目录按 mtime 倒序，最近用过的排前面。

新版 toybox 的 `stat` 支持 `%n`（文件名）；万一某个 ROM 不支持，取不到目录时退回
`ls -1` 只列名字（此时不排序），保证功能不至于整体失效。

### 2. 超时不再由读取异常顶替

`RootCommandExecutor` 原来用两个 `async { readText() }` 收输出。超时后 `process.destroy()`
关掉管道，阻塞中的读取抛 `read interrupted by close() on another thread`；`async` 失败会
取消父作用域，于是这个 Java 异常顶替了「命令超时」的结果，直接显示到界面上。

改成：stdout/stderr 各用一条读取线程边读边写进 `StringBuffer`，读取异常只记日志；
超时后 `destroy()` + 主动关流 + `join(1s)` 收尾，返回 `timedOut = true` 并带上已读到的部分输出。

### 3. 界面按页追加，滚到底再取

- `CacheRepository.loadPage(hash, sources, cursor)` 返回 `SourcePage(files, nextCursor, error)`，
  `nextCursor` 为 null 表示到底。
- `GalleryViewModel` 记住游标，新增 `loadMore()`；`GalleryUiState` 加 `hasMore` / `loadingMore`。
- `GalleryScreen` 用 `snapshotFlow` 监听最后一个可见项，进入倒数第 6 项就触发下一页；
  列表末尾是 `LoadMoreFooter`：加载中显示环形进度，未在加载时给一个可点的「加载更多」兜底。
- 每页单独排序，不对已加载的全部内容重排，否则新页会把列表顶得乱跳。
- 列表 key 从 `file.name` 改成 `file.key`：分页后不同子目录可能撞出同名文件，重名 key 会让
  `LazyVerticalGrid` 直接抛异常。

## 验证

单测（50 个用例）：

- `WxPathsTest.recursiveSourceListsSubDirsThenOneDirPerPage`：子目录命令含 `stat -c '%Y %n'`，
  分页命令含 `maxdepth 3`、`xargs -0 -n 200`、`head -n 400`，不含 `-printf`、不逐文件 `$(stat`。
- `WxPathsTest.pagedCommandsRejectUnsafeDir` / `flatSourceCannotBePaged`：非法目录名、扁平源分页都抛异常。
- `CacheRepositoryTest.newestSubDirIsScannedFirst`：mtime 更大的子目录先出，游标依次推进到底。
- `CacheRepositoryTest.firstPageFailureIsReportedWithTimeoutMessage`：首页失败才把错误交给界面。
- `CacheRepositoryTest.fallsBackToDirNamesWhenStatLacksNamePlaceholder`：`stat` 取不到名字时退回 `ls -1`。

`testOptions.unitTests.isReturnDefaultValues = true`：仓库层现在会写 AppLog，单测里
`android.util.Log` 未 mock 会抛异常，需要这个开关。

真机：进入「图片」页应在 1 秒内出第一批图，向下滚动自动续页，底部出现加载动画。
