# 分页扫描字段解析修复

## 背景

上一版把递归源（聊天图片 `image2`、朋友圈 `sns`）从「一次 `find` 全树」改成「一页扫一个一级子目录」，
解决了 30 秒超时。但真机上图片页改成提示「该账号没有此类型的缓存」，一个文件都不显示。

## 根因

shell 侧拼接命令时写成了：

```sh
... | while IFS= read -r sz mt path; do
```

`IFS=` 是「把 IFS 临时设为空字符串」，不是「保持默认 IFS」。IFS 为空时 `read` **不做字段拆分**，
整行全部落进第一个变量 `sz`，`mt` 与 `path` 留空。

设备日志里的现场完全对得上：

```
[repo] subdirs chat_image: 257
[repo] page parse empty chat_image/e0 lines=87
       sample = <TAB>6004 1790336752 /data/.../th_e0d720d9...<TAB><TAB>-<TAB><TAB>0
```

`sz` 里是「大小 时间 路径」三合一，`${path#"$D"/}` 因此得到空串，`${rel##*/}` 也是空，
六字段输出退化成「文件名空、相对路径空、中间一列挤成一团」。Kotlin 侧 `parseLine` 拿
`parts[1]`（含空格）去 `toLongOrNull()` 必然失败，整页 87 行全部丢弃。

单变量写法 `while IFS= read -r f` 是正确的（保护首尾空白），所以之前的扁平源、旧递归源都没暴露；
一旦换成多变量，`IFS=` 就从「保护空白」变成了「禁拆字段」。

本机对比实测（同一行 `stat` 输出）：

```
while read -r sz mt path   → sz=[7305] mt=[1795264299] path=[./aa/th_e0aa...]
while IFS= read -r sz mt path → sz=[7305 1795264299 ./aa/th_e0aa...] mt=[] path=[]
```

## 改动

1. **`WxPaths.listSourceDirPageCommand`**：`while IFS= read -r sz mt path` → `while read -r sz mt path`，
   用默认 IFS（空格 / tab / 换行）。`read` 的最后一个变量会接收剩余全部内容，所以路径含空格也不会被拆散。

2. **不再把「解析不出任何一行」当成空目录**。`CacheRepository.listDirPage` 原来在
   「有输出但 `files` 为空」时只记一条日志，`error` 仍是 null，于是被分页逻辑当作空目录静默跳过，
   界面最终显示「没有缓存」——把真正的解析 bug 藏了起来，这次排查多花了一轮。
   现在改为：`exitCode == 0` 且有输出却解析为空时返回 `文件列表格式异常`，界面上会直接看到，
   日志里同时留下样本行。命令失败（含超时）仍优先报超时；超时但有部分可解析内容则照旧先用着。

3. **`RootCommandExecutor` 的命名空间探测加互斥**。`mode` 原来是「为 null 就探测」，并发首次调用
   各探一遍，日志里能一次看到二十多条 `visible=` 探测；每次探测要跑三条 `su`，在生产环境下是几百毫秒的
   固定开销，分页时会被放大。改为 `Mutex` 双检，全生命周期只探一次。

## 命令实测

生成的分页命令（已在本机 bash 构造同名目录跑通，输出 6 列）：

```sh
D='/data/user/0/com.tencent.mm/MicroMsg/<hash>/image2'
P='/data/user/0/com.tencent.mm/MicroMsg/<hash>/image2/e0'
[ -d "$P" ] || exit 0
find "$P" -maxdepth 3 -type f \( -name 'th_*' \) 2>/dev/null | head -n 400 | tr '\n' '\0' \
  | xargs -0 -n 200 stat -c '%s %Y %n' 2>/dev/null | while read -r sz mt path; do
  rel=${path#"$D"/}
  printf '%s\t%s\t%s\t-\t%s\t0\n' "${rel##*/}" "$sz" "$mt" "$rel"
done
```

本机输出：

```
th_e0aa1234567890abcdef1234567890ab<TAB>7305<TAB>1795264299<TAB>-<TAB>e0/aa/th_e0aa...<TAB>0
```

六列齐整，相对路径 `e0/aa/th_<32hex>` 也符合 `CHAT_IMAGE` 的正则。

## 验证

- 新增 `CacheRepositoryTest.unparsablePageIsReportedInsteadOfTreatedAsEmptyDir`：用真实错位样本断言
  返回 `文件列表格式异常`，而不是空列表。
- `WxPathsTest` 增加断言：命令含 `while read -r sz mt path`，且不含 `IFS= read -r sz`。
- `testDebugUnitTest` 52 个用例全绿；`assembleDebug`、`lintDebug` 通过；
  确认 `while read -r sz mt path` 字符串已进入 `classes8.dex`。

## 范围外

- 子目录里的文件数超过 `head -n 400` 时，该目录只显示前 400 个（按 `find` 顺序，未排序）。
  微信单个 `xx/yy` 目录实际只有几十到一百多个文件，暂不处理。
- 图片页首屏仍需 0.3–0.7 秒：每次分页都要起一个 `su` 进程，主要是 su 的启动开销。
