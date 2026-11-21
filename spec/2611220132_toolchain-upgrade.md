# 技术栈升级到 AGP 9 / Gradle 9 / Kotlin 2

## 背景

原组合（AGP 8.10.1、Gradle 8.11.1、Kotlin 1.9.24、JDK 17）能正常构建，但已落后主流一代。本次一次性迁移到当前稳定线，避免后续逐个组件升级带来的重复改动。

## 版本组合（2026-11-22 核实的当前稳定版）

| 项 | 升级前 | 升级后 |
| --- | --- | --- |
| Gradle wrapper | 8.11.1 | 9.9.2 |
| AGP | 8.10.1 | 9.5.0 |
| Kotlin | 1.9.24 | 2.4.21 |
| Compose Compiler | 独立 1.5.14 | 随 `kotlin-compose` 插件，版本跟 KGP |
| 运行 Gradle 的 JDK | 17 | 21（Temurin 21.0.13.1） |
| 源码/字节码目标 | Java 17 | Java 17（不变） |
| Build Tools | 35.0.0 | 36.0.0 |
| compileSdk / targetSdk | 35 / 35 | 35 / 35（不变） |

说明：

- Gemini 建议中的 "AGP 9.5.1" 并不存在，最新稳定版是 9.5.0（2026-11-05）；9.5.1 是 Gradle 的版本号。
- Gradle 9.9.2（2026-11-20）修复了 4 个安全漏洞，直接用最新版，不用 9.6.0。
- Kotlin 2.4.21 为当前稳定版，Compose Compiler 从 2.0 起随 KGP 分发，不再单独声明 `kotlinCompilerExtensionVersion`。
- AGP 9 要求 Build Tools 最低 36.0.0；NDK 28.2.13676358 与 CMake 3.22.1 恰好是 AGP 9 的默认版本，保持不变。
- compileSdk 保持在 35：新版本 Compose 依赖尚未强制 37，先让工具链升级本身独立通过验证；升 API 级别是另一件事，等到要用 Android 17 特性或上架政策要求时单独做。
- JDK 17 仍是 AGP 9 的最低要求，升到 21 只为 Gradle 守护进程的 GC 与吞吐；`jvmTarget` 固定 17，避免 ART 脱糖负担变化。

## 构建脚本改动

- 根 `build.gradle.kts` 与 `app/build.gradle.kts`：删掉 `org.jetbrains.kotlin.android`（AGP 9 内置 Kotlin，与新 DSL 不兼容），应用 `org.jetbrains.kotlin.plugin.compose`。
- `app/build.gradle.kts`：`android.kotlinOptions { jvmTarget }` 迁到顶层 `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`；删掉 `composeOptions { kotlinCompilerExtensionVersion }`。
- `libs.versions.toml`：新增 `kotlin-compose` 插件别名，移除 `composeCompiler` 版本项。
- `gradle.properties`：`org.gradle.java.home` 指向 JDK 21。
- `.github/workflows/release.yml`：`setup-java` 改 21，`setup-android` 的 build-tools 改 36.0.0；保留删除 `org.gradle.java.home` 与 `bash ./gradlew` 两处兼容处理。
- `README.md`：同步环境要求、CI 描述与技术栈清单。

## 未使用新 DSL 的可选项

没有使用旧版 Variant API（`applicationVariants`、`variantFilter` 等），签名配置用的是 `signingConfigs { create(...) }` 声明式写法，在 AGP 9 新 DSL 下原样可用，不需要 `androidComponents` 迁移。

## 验证

- `./gradlew help`：Gradle 9.9.2 插件解析通过。
- `testDebugUnitTest`（80 个用例）、`lintDebug`、`assembleDebug`、`assembleRelease` 全部成功；AGP 9 的 R8 优化资源压缩、Compose mapping 校验、四个 ABI 的 CMake 构建均正常。
- `zipalign -c -P 16 4`：通过，原生库仍按 16 KB 页对齐。
- `apksigner verify --print-certs`：通过，签名证书不变（CN=zsyo）。
- debug 包通过 MT MCP 推送安装到真机，安装成功。

## 附带修正

工作区里 `README.md` 的 `keystore.properties` 示例块残留了一处未提交的误改（`key` 与 `value` 都被换成了随机串），已还原为历史提交中一直使用的 `keyAlias=wxcacheviewer`，与下方 keytool 命令的 `-alias wxcacheviewer` 保持一致。该随机串经比对并非真实密钥库的别名或口令，历史提交中也从未出现过，不涉及凭据泄露。
