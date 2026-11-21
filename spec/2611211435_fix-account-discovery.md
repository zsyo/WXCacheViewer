# 修复账号发现为空

## 背景
真机（已授权 Magisk root）安装后打开应用，root 检测通过但账号列表为空，界面显示"未发现微信账号"。

## 原因分析
原实现用一条 shell 管道发现账号：

```
ls -1 <cache> <identity_records> | grep -E '^[0-9a-f]{32}$' | sort -u || true
```

两个脆弱点：
1. `ls` 失败（权限/别名/输出被重定向）时 stderr 被丢弃，管道产出空字符串；
2. `grep -E` 在部分设备的 shell 环境中不可用或行为不一致，同样会把整条管道吞成空输出；
3. 结尾的 `|| true` 让空结果的退出码仍为 0，上层无法区分"没有账号"和"命令失败"。

## 方案
- 新增 `AccountDiscoveryParser`：纯 Kotlin 解析命令输出，识别两类行——`*/finder/video` 结尾的绝对路径（该账号有视频缓存）与裸 32 位十六进制名（cache 目录或 identity_records 条目），并集去重、有缓存的排前。
- `WxPaths.discoverAccountsCommand()` 改为 `find`：分别对 `/data/data/com.tencent.mm` 与 `/data/user/0/com.tencent.mm` 枚举 `cache` 下一级目录、`cache` 下三级内名为 `video` 的目录、`files/identity_records` 下一级条目；不再依赖 `ls`/`grep`。
- `CacheRepository.discoverAccounts()` 只在命令非零退出且无输出时抛错，其余交给解析器。
- 新增 `RootDiagnostics`：账号为空或报错时采集 `id`、`su` 路径、两个基址的 `ls`/`find`/glob 输出与发现命令的原始返回，展示在错误页并落盘（应用内 `files/diagnostics.txt`，并通过 root 复制到 `/sdcard/MT2/mcp/diagnostics.txt` 便于无 adb 时取回）。

## 完成标准
`testDebugUnitTest` 全部通过（新增 `AccountDiscoveryParserTest` 5 例）；真机打开应用能列出账号。
