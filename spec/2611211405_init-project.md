# 初始化工程骨架

## 目标
在空仓库中建立可编译的 Kotlin + Jetpack Compose Android 工程，确认本机构建环境可用。

## 方案要点
- Gradle wrapper 8.11.1；AGP 8.10.1；Kotlin 1.9.24；Compose Compiler 1.5.14；Compose BOM 2024.12.01。
- compileSdk/targetSdk 35，minSdk 24，jvmTarget 17。
- 包名 `top.aico.wxcacheviewer`，中文应用名“微信缓存查看器”。
- Android SDK 安装到 `E:/Dev/Android/Sdk`，JDK 17 路径写入 `gradle.properties`。
- 仓库依赖优先阿里云镜像，保留 google()/mavenCentral()。
- 只放一个最小 Activity 与 MaterialTheme，不实现业务。

## 完成标准
`./gradlew assembleDebug` 成功生成 app-debug.apk。
