# 依赖库升级到当前稳定版

## 背景

Compose BOM 仍停留在 2024.12.01（近两年），其余 AndroidX 依赖也落后多个版本。旧版本可能有已知缺陷与安全问题，版本差距持续积压会抬高后续迁移成本。本次将直接依赖一次性升到核实过的当前稳定版（2026-11-22）。

## 升级清单

| 依赖 | 升级前 | 升级后 |
| --- | --- | --- |
| Jetpack Compose BOM | 2024.12.01 | 2026.09.00 |
| Material3 | 1.3.1（BOM 带入） | 1.4.0（BOM 带入） |
| Media3 | 1.5.1 | 1.11.1 |
| androidx.core:core-ktx | 1.15.0 | 1.19.1 |
| androidx.activity:activity-compose | 1.9.3 | 1.13.0 |
| androidx.lifecycle | 2.8.7 | 2.11.1 |
| Coil | 2.7.0 | 3.6.3（大版本迁移） |
| AGP | 9.5.0 | 9.5.0（不变，已是最新稳定） |
| Kotlin | 2.4.21 | 2.4.21（不变，已是最新稳定） |
| JUnit | 4.13.2 | 4.13.2（不变，4.x 最新） |
| NDK / CMake | 28.2.13676358 / 3.22.1 | 不变（16 KB 页对齐约束） |
| compileSdk / targetSdk | 35 / 35 | 37 / 35 |
| Build Tools | 36.0.0 | 37.0.0 |

说明：

- Compose 1.12（BOM 2026.08 起）与 Coil 3.6 均要求 **compileSdk 37**，因此 compileSdk 必须升到 37；本地 SDK 已有 `platforms;android-37`，另安装 `build-tools;37.0.0`。
- **targetSdk 保持 35**：它与 compileSdk 无关，升高会引入 Android 16/17 的运行时行为变更（前台服务、权限、边缘到边缘等）。本项目依赖 root shell 与私有目录访问，需单独回归，不在本次依赖升级范围内。
- AGP 9.5.0、Kotlin 2.4.21 已是当前最新稳定版；Gemini 曾提到的 "AGP 9.5.1" 不存在。NDK/CMake 版本固定以维持 16 KB 页对齐，不随依赖升级改动。
- JUnit 4.13.2 是 4.x 最新；测试代码基于 JUnit 4，不迁移到 Jupiter。

## Coil 2 → 3 迁移要点

Coil 3 属于大版本迁移，改动集中在图片加载封装与两处调用点：

1. **坐标组**：Maven group 由 `io.coil-kt` 改为 `io.coil-kt.coil3`，`coil-compose`、`coil-gif` 均随之调整。
2. **包名**：Kotlin 包由 `coil` 改为 `coil3`，`AsyncImage` 的 import 从 `coil.compose.AsyncImage` 改为 `coil3.compose.AsyncImage`。
3. **API 变化**（以 3.6.3 实际字节码为准，官方文档仍停留在 3.0）：
   - `serviceLoaderEnabled(false)` 改为 `coil3` 包的顶层扩展函数，需 `import coil3.serviceLoaderEnabled`。
   - `crossfade(false)` 移到 `coil3.request` 包的顶层扩展，需 `import coil3.request.crossfade`。
   - `MemoryCache.Builder(context)` 改为无参构造，`maxSizePercent` 改收 `Context` 参数。
   - `DiskCache.Builder().directory()` 由 `java.io.File` 改为 `okio.Path`，调用处补 `.toOkioPath()`。
   - 动图解码器：`ImageDecoderDecoder` 在 API 28+ 由 `coil3.gif.AnimatedImageDecoder` 承担，低版本仍用 `coil3.gif.GifDecoder`；静态图解码由 coil-core 默认组件处理。
4. **保持原有语义**：两个 `ImageLoader` 都显式 `serviceLoaderEnabled(false)`，避免 coil-gif 经 ServiceLoader 自动注册导致 static 实例也解码动图；仅 animated 实例手动注册动图解码器，维持「网格只解首帧、全屏预览才解码动图」的行为。

## 附带修正

1. Material3 1.4 起不再传递依赖 `androidx.compose.material:material-icons-core`，而项目多处使用了 `androidx.compose.material.icons.Icons`。此前靠旧版传递依赖白嫖图标库，升级后会编译失败。已在版本目录显式声明该依赖，版本仍由 Compose BOM 统一管理。
2. Compose UI 1.7 起新增 lint 规则 `LocalContextGetResourceValueCall`：`LocalContext.current.getString()` 不会随 Configuration 变化刷新，可能取到过期文案。7 处位于协程/lambda 回调中的调用（`stringResource` 无法在回调里用）改用 configuration-aware 的 `LocalResources.current.getString()`；`PlayerScreen` 的 displayMetrics 读取同步改用 `LocalDensity.current`。

## CI 与文档

- `.github/workflows/release.yml`：`setup-android` 的 packages 改为 `platforms;android-37 build-tools;37.0.0`；签名校验取最高 build-tools 的逻辑不变。
- `README.md`：环境要求改为 Platform 37 + Build-Tools 37.0.0，技术栈清单同步新版本号。
- 历史 spec 保留当时的版本记录，不回改。

## 验证

- `testDebugUnitTest`（80 个用例）、`lintDebug`、`assembleDebug`、`assembleRelease` 全部成功；修复后编译与 lint 均零错误、零警告（Material3 的 `ScrollableTabRow` 弃用已迁到 `PrimaryScrollableTabRow`）。
- 四个 ABI 的 release 包（arm64-v8a / armeabi-v7a / x86 / x86_64）均通过 `zipalign -c -P 16 4` 的 16 KB 页对齐校验与 `apksigner verify` 签名校验。
- arm64-v8a debug 包经 MT MCP 推送安装到真机成功；Coil 3 迁移后的图片网格与动图预览、语音播放待真机交互确认。
- 依赖解析用 `:app:dependencies --configuration debugRuntimeClasspath` 复核：compose-bom 2026.09.00、material3 1.4.0 均由 BOM 统一管理，material-icons-core 显式声明后正常解析。
