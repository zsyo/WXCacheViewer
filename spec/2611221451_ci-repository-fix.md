# 修复 CI 依赖解析失败

## 问题
CI 在 `:app:lintAnalyzeDebug` 阶段失败：`com.android.tools:repository:32.5.0` 等 lint 工具依赖无法解析。
根因是 `settings.gradle.kts` 把阿里云镜像放在官方仓库之前，阿里云 `releases` 仓库返回 502 后，Gradle
将仓库标记为不可用并中断解析，后面的 `google()` / `mavenCentral()` 没能救回来。
同时 `VoiceFormatTest` 末尾遗留的 `Unit` 表达式被新 Kotlin 编译器警告为未使用表达式。

## 方案
- `dependencyResolutionManagement.repositories` 只保留 `google()` 与 `mavenCentral()`；
  需要国内镜像的开发者在用户级 `~/.gradle/init.gradle.kts` 自行配置，不影响 CI。
- 删除 `VoiceFormatTest.kt` 中多余的 `Unit`。
- 更新初始化规格文档中对依赖仓库的描述，避免后续再把镜像写回仓库。

## 完成标准
- CI 的 `Build signed release` 步骤能解析 lint 工具依赖并完成构建。
- 本地 `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease` 全过，无新增警告。
