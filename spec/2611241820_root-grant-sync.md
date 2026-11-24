# 权限：授权状态变化后的同步

## 现象

1. 首次启动没有授权，显示引导页；去 root 管理器点「允许」后回到应用点「重新检测」，
   结果是「未发现微信账号」，下方还挂着诊断信息。**杀掉应用重开**才正常进账号选择页。
2. 反过来，在应用运行期间把授权改成「拒绝」，之后任何需要 root 的操作只显示失败或空列表，
   界面停在相册里，不知道要去重新授权。

两者都是「授权状态变了，应用里的结论没跟着变」。

## 根因：把「探不出来」当成了结论

`RootCommandExecutor` 用三种方式执行 su，首次调用时探测一次并缓存：

| 模式 | 命令 | 适用 |
|---|---|---|
| `DIRECT` | `su -c` | 继承应用命名空间 |
| `MOUNT_MASTER` | `su -mm -c` | Magisk / KernelSU 的全局挂载命名空间 |
| `NSENTER` | `su -c "nsenter -t 1 -m -- sh -c …"` | 进 init 命名空间 |

探测靠一条命令比较 `/data/user/0` 的可见目录数与已安装包数。**没授权时三条探测全部失败，
旧实现却仍然 `return NamespaceMode.DIRECT` 并缓存**。于是：

- 用户在 root 管理器点了允许，回来点重新检测；
- `checkRoot()` 里 `run("id -u")` 命中缓存 → 走 `su -c` → 返回 `0`，看似一切正常；
- 但 `su -c` 继承的是**应用被裁剪的挂载命名空间**，`/data/user/0/com.tencent.mm` 不可见；
- `discoverAccounts()` 读不到微信目录 → 账号数量为 0 → 显示「未发现微信账号」。

真机诊断里的 `namespaceMode=DIRECT` 与 `ls -la /data` 的空白输出正是这个状态的证据；
杀掉进程后 `mode` 为空，重新探测时授权已在，于是升级到 `MOUNT_MASTER`，一切正常。

## 修复一：无效结论不缓存

- `resolveMode()` 返回 `NamespaceMode?`：su 不存在（`-127`）、探测超时、或 su 返回非 0 且没有
  探针输出时返回 **null**，表示「这次没有结论」。`currentMode()` 遇到 null 只把本次调用降级为
  `DIRECT`，**不写入缓存**。
- 顺带省时间：su 被拒绝时立即返回，不再依次尝试 `-mm` 与 `nsenter`（那两条同样会被拒或超时）。
- `checkRoot()` 开头调用新增的 `invalidateMode()`：用户点「重新检测」通常就是刚授权回来，
  必须重探命名空间。缓存清空后下一次执行会重新探测。
- 探针命令正常输出但命名空间仍不完整，且 `-mm` / `nsenter` 都不可用，才把 `DIRECT` 当结论缓存
  —— 那是设备限制，不会因为授权变化而改变。

## 修复二：回到前台复检授权

`verifyRoot()` 走同一套判定，但**不重探命名空间**：沿用已确定的执行方式问一次 `su -c id -u`，
只多一次 su 调用。授权被撤销时返回 `DENIED`，恢复时返回 `Available`。

- `AppRoot` 用 `LifecycleEventEffect(ON_RESUME)` 触发 `AccountViewModel.verifyRoot()`。
  撤销授权必然要离开应用去 root 管理器，所以回到前台一定会经过这个点。
- 检测到不可用时更新 `rootState`；`AppRoot` 的显示条件加上
  `rootState is Unavailable` —— 即使选过账号也退回引导页。账号选择本身保留，
  重新授权后不用再选一次。
- 检测到已恢复且当前停在引导页时，自动 `refresh()` 重拉账号，界面自己回到相册，不用手动点按钮。
- 防重入：首屏 `refresh()` 或用户点「重新检测」正在跑时，复检直接跳过，避免两份结论互相覆盖。

## 测试

`RootCommandExecutorTest`，用覆写 `execute` 的假设备模拟「Android 17 上 `su -c` 视角被裁剪、
只有 `su -mm` 完整」：

- `deniedProbeIsNotCachedSoRetryUpgradesAfterGrant`：未授权时 `currentMode()` 降级为 `DIRECT`，
  授权后同一实例重探拿到 `MOUNT_MASTER`。**这条用例在旧实现下必然失败**
  （`expected:<MOUNT_MASTER> but was:<DIRECT>`），已验证。
- `checkRootReflectsNewGrantWithoutRestart`：不重启进程，`DENIED` → `Available`。
- `resolvedModeIsReusedUntilInvalidated`：已确定的模式不重复探测，`invalidateMode()` 后重探。
- `verifyRootReusesResolvedModeAndSeesRevokedGrant`：复检只花一次 su，能发现撤销与恢复。
- `missingSuReportsNoSuInsteadOfDenied`：没有 su 二进制时仍报 `NO_SU` 而不是 `DENIED`。

## 限制

复检绑定在 `ON_RESUME` 上。若通过分屏等让应用始终处于前台、同时改授权，不会立即发现；
下一次回到前台或点任何会失败的操作后仍会收敛到引导页。
