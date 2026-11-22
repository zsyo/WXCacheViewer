# 关于页：手动检查更新

## 需求

在「关于」里增加检查更新能力，但保持「不主动联网」的定位：

1. 用户可以选择**正式版**或**预发布版**两个渠道。
   - 正式版：只认 GitHub 上被标记为 latest 的版本，预发布与草稿都不提示。
   - 预发布版：在全部已发布版本里取版本号最大的，只要有更新就提示，不区分 latest 和预发布。
2. **只有**用户点「检查更新」才发起请求；应用启动、进设置页、回到前台都不会联网。
3. 同时修正关于页文案：补上之前漏掉的「视频号视频」和「表情」，并把「不联网，不上报数据」改成符合现状的说法。

## 渠道语义

GitHub Release 的 `prerelease` 标记与「latest」是两个概念，接口也分两条：

| 渠道 | 请求 | 挑选规则 |
| --- | --- | --- |
| 正式版 | `GET /repos/{repo}/releases/latest` | 由 GitHub 保证是最近的非预发布版本；仓库还没发版时返回 404 |
| 预发布版 | `GET /repos/{repo}/releases?per_page=20` | 过滤 `draft`，按 `tag_name` 解析出版本号后取最大 |

预发布渠道不用「接口返回的第一个」，因为 GitHub 的列表按创建时间倒序，补发的旧版本 tag 也会排在最前面；本地按版本号比较才不会给用户推一个更旧的包。

版本比较实现在 `domain/update/SemanticVersion.kt`：

- 支持 `v` 前缀、省略 patch（`0.1`）、`+build` 元数据；
- 同核心版本时，`1.0.0-beta < 1.0.0`；
- 预发布段按 semver 规则比较：数字段按数值、数字段低于字母段、前缀相同时段数多的更新；
- 解析失败（手动触发构建的 `dev-<sha>`）不参与比较，直接展示查到的最新版本。

## 安装包选择

CI 把产物拆成四个 ABI 包，命名 `WXCacheViewer-<版本>-<abi>.apk`。检查到新版本后，按 `Build.SUPPORTED_ABIS` 的顺序找第一个名字以 `-<abi>.apk` 结尾的附件，用它的 `browser_download_url`；找不到就退回 Release 页面，让用户自己挑架构。

不在应用内下载安装：那需要 `REQUEST_INSTALL_PACKAGES` 与额外的下载/校验逻辑，交给系统浏览器更简单，也不会因应用被杀而留下半个文件。

## 联网边界

- 新增 `android.permission.INTERNET`，仅用于这一次手动请求。
- 请求是 `HttpURLConnection` 的单个 GET，带 `User-Agent: WXCacheViewer/<versionName>`、`Accept: application/vnd.github+json`，连接 10s、读取 15s 超时，响应体限制 2 MB。
- 走 HTTPS（`api.github.com`），不涉及明文流量，因此不需要 network security config。
- 错误分四类映射成中文提示：网络不可用、GitHub 限流（403/429）、其它 HTTP 状态码、返回内容无法解析。
- 隐私说明与 README 同步改写：明确「只在你点检查更新时访问 GitHub 公开 API，请求只带应用名与版本号」。

## 界面

「关于」卡片在版本号下方新增两行，与仓库、开发者等行同样的版式：

- **更新渠道**：右侧显示当前渠道，点击弹出单选对话框，选中即写入偏好并关闭。
- **检查更新**：点击后右侧显示「检查中…」与环形进度，期间该行不可点；结果分三种：
  - 有新版本：弹出对话框，展示当前版本、最新版本（预发布会标注）、发布时间与更新说明（可滚动，最高 360dp），按钮「去下载」按 ABI 打开直链，「关闭」只收起对话框。
  - 已是最新：Snackbar 提示「已是最新版本（x.y.z）」。
  - 失败/无版本：Snackbar 提示具体原因。

渠道选择持久化在 `SettingsRepository.updateChannel`，默认正式版。检查状态放在 `SettingsViewModel`，转屏后对话框与「检查中」状态不丢。

## 实现位置

| 文件 | 作用 |
| --- | --- |
| `domain/update/SemanticVersion.kt` | 版本号解析与比较，纯 Kotlin |
| `domain/update/UpdateChannel.kt` | 正式版 / 预发布版枚举 |
| `domain/update/ReleaseInfo.kt` | Release 摘要与按 ABI 选安装包 |
| `data/update/UpdateChecker.kt` | GitHub API 请求、错误分类 |
| `data/update/ReleaseParser.kt` | JSON 解析与版本挑选，可脱离网络单测 |
| `data/repository/SettingsRepository.kt` | 渠道持久化 |
| `viewmodel/SettingsViewModel.kt` | 手动检查、结果判定、错误文案 |
| `ui/settings/SettingsScreen.kt` | 两行入口、渠道单选、更新对话框 |
| `ui/common/AppInfo.kt` | 关于页与检查更新共用的 versionName 读取 |

## 边界

- 未登录的 GitHub API 每个 IP 每小时 60 次，正常手动点不会碰到；被限流时提示稍后再试，不自动重试。
- 当前版本号解析失败（`dev-xxxx`）或未安装正式包时，只要有 Release 就提示新版本，不强求比较结果。
- 用户点「去下载」后应用不做任何后续处理，下载与安装完全由浏览器与系统安装器负责。
- 发布流程仍是推 `v*` tag；`prerelease` 由 CI 根据版本号里是否含 `-` 自动判定，与应用的渠道选择对应。

## 验证

- `SemanticVersionTest` 5 条：前缀与 build 元数据、缺省 patch、非法输入、数值比较、预发布优先级。
- `ReleaseParserTest` 5 条：字段映射、草稿过滤、`newest` 取最大、正式版压过同核心预发布、无版本时返回 null。
- `ReleaseInfoTest` 2 条：按 ABI 命中选择安装包、无匹配时退回 Release 页面。
- `UpdateCheckerTest` 6 条：起一个本地 `HttpServer`，验证 latest 与列表两条路径、`User-Agent` 透传、404 视为无版本、403/429 归为限流、其它状态码带出 code、非 JSON 报解析错误；不依赖外网。
- `testDebugUnitTest` 共 102 条全绿，`lintDebug` 无新增问题，`assembleRelease` 产出四个 ABI 包，签名与 16 KB 页对齐仍通过。
