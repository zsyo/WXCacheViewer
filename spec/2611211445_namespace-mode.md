# 提升 su 挂载命名空间

## 背景
真机为 Android 17（sdk=37，KernelSU 环境）。账号发现仍为空，诊断文件显示：

```
appSees /data/user/0/com.tencent.mm=false
== data-user-0 ==   com.google.android.gms  com.xiaomi.aiservice  top.aico.wxcacheviewer
== data-data ==     同上，仅 3 项
== dumpsys-dataDir == dataDir=/data/user/0/com.tencent.mm
== nsenter-global == /data/user/0/com.tencent.mm
== su-mount-master == /data/user/0/com.tencent.mm
```

即：`su -c` 默认运行在应用进程的挂载命名空间里，该命名空间已被 Android 17 裁剪为只包含少量目录，
其他应用的数据目录即使对 root 也表现为 ENOENT（不是 EACCES）；而 `nsenter -t 1 -m` 与 `su -mm`
能进入 init 的全局命名空间，正常看到 `/data/user/0/com.tencent.mm`。

## 方案
- 新增 `NamespaceMode` 枚举：`DIRECT`（su -c）、`MOUNT_MASTER`（su -mm -c）、`NSENTER`（su -c "nsenter -t 1 -m -- sh -c …"）。
- `RootCommandExecutor` 首次使用时探测并缓存模式：执行
  `echo visible=$(ls -1 /data/user/0 | wc -l) packages=$(pm list packages | wc -l)`，
  `visible >= packages * 0.8` 视为命名空间完整（`pm` 不可用时按 30 兜底）。
  依次尝试 DIRECT → MOUNT_MASTER → NSENTER，全部失败则回退 DIRECT 并记录日志。
- 之后所有 su 命令按选中的模式包装执行，文件路径本身不变。
- 新增 `AppLog`：`files/logs/app.log`（256KB 滚动），记录每条 su 命令、退出码、stdout/stderr 摘要，
  以及账号发现结果与命名空间决策，便于无 adb 时取证。

## 完成标准
`testDebugUnitTest`、`assembleDebug` 通过；真机诊断日志出现 `mode=MOUNT_MASTER` 或 `mode=NSENTER`，
账号列表非空。
