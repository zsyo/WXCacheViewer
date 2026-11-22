# 全量代码审查与遗留问题修复

时间：2026-11-22 13:35

## 背景

技术栈、依赖 ABI 拆分、签名与 debug 包名隔离完成后，对全项目做一次系统性审查，从数据层、领域层/ViewModel、UI 与构建配置三个视角并行排查遗留 bug，以及升级 Kotlin 2.4 / Compose 1.12 / Coil 3 / JDK 21 后可简化的写法。只修真实可触发的问题，不做纯风格重构，也不改动已验证放行的构建/CI 主干。

## 必修 bug

### 1. 语音导出采样率错误

`VoicePreparer.decodeMp3` 直接读 `.rate` 旁注决定采样率，而旁注只在播放过（走 `decodeWav`）之后才生成。未播放就导出时固定按 24kHz 编码，16kHz 老语音会被压成一半时长、音调升高。修法：导出前先调用 `decodeWav` 触发采样率探测，复用同一份旁注结论，不重复解码。

同时把采样率编进 wav 与 mp3 的缓存文件名（`${id}_${rate}.ext`）：采样率从 24k 改判为 16k 后，不会命中改判前生成的旧产物，避免错误被永久固化。

### 2. 微信私有封装语音的 SILK 缓存永远不命中

`normalizeSilk` 判断缓存时用 `kind == SILK && 长度 == raw.size`，但微信在标准头前多写一个 `0x02`，剥头后标准流比原始字节少 1 字节，且 kind 是 `WEIXIN_SILK`——两个条件都不成立，每次播放/导出都重写标准 SILK。改为按 `toStandardSilk` 结果的真实长度判断缓存，两种封装都能命中。

### 3. 翻页超时后游标按整页推进，文件永久丢失

`CacheRepository.flatSlice` 超时只拿到部分输出，却按 `offset + limit` 推进游标，中间没读到的文件本次会话再也翻不到。改为按实际拿到的行数 `offset + lines.size` 推进，并把「本页可能不完整」的提示透传给界面。递归源 `listDirPage` 的部分结果同样带错误提示，用户知道可下拉重试。

`subDirCache` 从 `HashMap` 改为 `ConcurrentHashMap`：多个加载协程会并发读写。

### 4. su 进程在协程取消时泄漏

`RootCommandExecutor.execute` 原先只在超时分支 `destroy()` 进程；外层协程被取消（切账号、退出页面）时 `CancellationException` 穿透 `withTimeoutOrNull`，清理代码全部跳过，su 子进程与读取线程残留。用 `try/finally` 包住等待，任何退出路径都 `destroy()` 进程并关闭管道。受 minSdk 24 限制用 API 1 的 `destroy()`，不用 `isAlive`/`destroyForcibly`（API 26+）。

### 5. 三个 ViewModel 的并发竞态

- `GalleryViewModel`：`load`/`refresh`/`loadMore` 各自启动协程，下拉刷新与在飞的 `loadMore` 并发时，旧的 `loadMore` 响应会把旧页数据追加到新列表、并用旧游标覆盖新游标，导致重复或跳页。引入 `loadGeneration` 代次令牌，每次 `load`/`refresh` 递增，在飞响应返回时代次已变就丢弃；`loadMore` 也识别 `refreshing` 状态。
- `AccountViewModel`：`refresh()` 可重入（首次进入 + 重试点击），旧响应会覆盖刚保存的备注。用 `refreshJob` 取消上一次；两个 catch 显式放行 `CancellationException`，避免被当成业务异常后继续写状态。`checkRoot()` 移入 try，抛异常时给出错误提示而不是让 loading 卡住。
- `SettingsViewModel`：扫描用 `scanGeneration` 代次取代 Job 引用，取消/清理后旧结果不再写回；`clearCache` 只遍历目录一次算剩余大小。

### 6. 播放器回调写在组合阶段

`PlayerScreen` 把 `videoPlayer.onPrepared` 等四个回调直接写在 composable 主体，每次重组（进度每 200ms 更新）都重新赋值，属组合阶段副作用。移入 `DisposableEffect(videoPlayer)` 只设置一次，用 `rememberUpdatedState` 让回调读到最新状态。

### 7. 切标签/账号时预览态残留

`GalleryScreen` 的 `remember` 状态不随 tab/账号退出组合，原清理 effect 只停了语音。补上清空视频播放、图片预览与待打开文件的 key，避免新列表里同 key 条目短暂显示错误 loading。

### 8. `produceState` 异常导致整屏崩溃

`GalleryScreen.CacheFileTile` 与 `ImageViewerScreen.ViewerPage` 的 producer 直接调用 root shell + 原生解码，异常会抛到组合。加 try/catch：网格退回占位图，预览页区分「加载中」与「失败」并显示错误文案，不再无限转圈。

## 效率与写法改进

- `CoilImageLoaders`：两个 loader 原先各自 new 一个 DiskCache 指向同一目录，两份独立 LRU 互相淘汰。改为共享同一实例；`init` 加 `@Synchronized`。
- `ExportNaming.shortDigest` / `VoicePreparer.cacheId`：摘要统一用 UTF-8 与 `Locale.US`，跨环境文件名稳定。
- `Formatters.formatFileSize`：改用 `Locale.US`，与 `formatDuration` 一致，避免部分语言下小数点变逗号。
- `SettingsScreen.versionName`：改 `PackageInfoFlags`（API 33+）并保留低版本分支，去掉 `@Suppress("DEPRECATION")`。
- `ExportLocation.resolve`：volume 段加白名单校验，防止 `..:foo` 之类路径遍历（原来只校验 relative 段）。
- 清理 `PlayerScreen` 重复的 `LocalDensity` import。

## 未改动的项（附理由）

- `gradle.properties` 的 `org.gradle.java.home`：CI 已用 sed 删除，本地构建依赖它指向 JDK 21，移除会导致本机无法构建；属开发机配置，非 bug。
- 分页全局排序：`GalleryViewModel` 逐页排序，跨页不全局有序。改成 repository 侧按排序字段切片是较大改动且会改变现有交互语义，本轮不动，已在结论中说明。
- `MediaPreparer`/`ThumbnailLoader` 的 per-key 锁、诊断命令合并等：低频路径，收益小，留待后续。

## 测试补充

- `ExportLocationTest`：非法 volume（`..`、`../etc`、`a/b`、空）必须拒绝。
- `ExportNamingTest`：聊天文件名的斜杠/反斜杠替换、`.`/`..` 与空白名回退到生成名。
- `CacheFileSorterTest`：相同值跨源时按完整 key 作为 tiebreaker。
- `CacheRepositoryTest`：扁平源超时只输出 2 行时，游标推进 2 而不是整页 5，且错误提示透传。

## 验证

- 84 个单测（较审查前增加 4 个）、`lintDebug`、`assembleDebug`、`assembleRelease` 全部通过，零警告零错误。
- 四个 ABI 的 release 包签名方案均为 v2=true / v3=true、v1/v4=false，证书 DN 仍为 `CN=zsyo, O=WXCacheViewer, C=CN`（指纹未变，老用户可覆盖升级）。
- debug 与 release 共八个包的 `zipalign -c -P 16` 全部通过，16KB 页对齐无退化。
- arm64 debug 包经 MT MCP 推送真机安装成功。
- 真机交互（语音导出采样率、翻页、切号）需使用者打开 app 确认，本轮只验证到安装环节。
