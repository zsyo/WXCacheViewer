# Root 命令通道与路径安全校验

## 目标
为 App 提供唯一的高权限命令入口，并保证所有拼入 shell 的路径都经过白名单与转义校验。

## 方案要点
- App 进程不提权；通过 `ProcessBuilder("su", "-c", command)` 派生高权限 shell，只解析 stdout。
- `RootCommandExecutor` 提供 `run` 与 `checkRoot`，识别无 su、授权拒绝、超时。
- `WxPaths` 集中管理微信路径常量、账号 hash 与缓存文件名正则、`shellQuote` 转义，以及目录列举、账号发现、文件复制命令的构造。
- 用户备注不进入 shell 命令。

## 完成标准
`./gradlew test --tests "*WxPathsTest"` 通过；`assembleDebug` 通过。
