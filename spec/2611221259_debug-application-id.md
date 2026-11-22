# debug 包使用独立 applicationId

时间：2026-11-22 12:59

## 背景

debug 包用 Android 自动生成的调试密钥签名，正式包用项目自己的 `release.keystore` 签名，两者签名不同。Android 以「applicationId + 签名证书」识别应用，签名不同但 applicationId 相同时无法覆盖安装，会报签名冲突。此前用 MT 推送 debug 包到已装正式包的设备，必须先卸载正式包。

## 改动

在 `app/build.gradle.kts` 的 `buildTypes.debug` 增加：

```kotlin
applicationIdSuffix = "dev"
```

- debug 包 applicationId 由 `top.aico.wxcacheviewer` 变为 `top.aico.wxcacheviewer.dev`。
- release 包 applicationId 保持 `top.aico.wxcacheviewer` 不变。
- 两个包签名仍各自独立，但 applicationId 不同，可同时安装、互不覆盖。

## 为何不用下划线后缀

最初尝试 `applicationIdSuffix = "_dev"`，AGP 会拼成 `top.aico.wxcacheviewer._dev`。Android 要求包名每一段以字母开头，`_dev` 非法，AAPT 直接报错：

```
AAPT: error: attribute 'package' in <manifest> tag is not a valid Android package name
```

也评估过用 `androidComponents.onVariants` 里的 `variant.applicationId.set(...)` 直接写完整包名（可得到 `..._dev`），但该写法更重。标准 `.dev` 后缀满足需求且符合 Android 惯例，最终采用它。

## 连带影响（已确认安全）

- `AndroidManifest.xml` 里 FileProvider 写的是 `${applicationId}.fileprovider`，合并后自动变成 `top.aico.wxcacheviewer.dev.fileprovider`。
- `FileOpener.kt` 里通过 `context.packageName + ".fileprovider"` 取 authority，运行时拿到的是带后缀的包名，与 manifest 一致，系统「打开方式」不受影响。
- `SettingsScreen` 里通过 `context.packageManager.getPackageInfo(context.packageName, 0)` 取版本号，跟随 debug 包名，正常。
- Kotlin 源码包名（namespace）仍是 `top.aico.wxcacheviewer`，不受 applicationId 影响，所有 import 无需改动。

## 应用名区分（后续补充）

两个包最初都取自同一个 `app_name` 字符串，桌面显示同名，只能靠包名区分。后来增加 debug 专属资源覆盖，新增 `app/src/debug/res/values/strings.xml`，仅重定义 `app_name`：

```xml
<string name="app_name">微信缓存查看器（开发版）</string>
```

debug sourceSet 的资源在合并时覆盖 main 的同名项，其余字符串仍从 main 继承，无需重复声明。debug 包应用名为「微信缓存查看器（开发版）」，release 仍为「微信缓存查看器」。

## 验证

- 80 个单测、`lintDebug`、`assembleDebug`、`assembleRelease` 全部通过，零警告零错误。
- 四个 ABI 的 debug 包 package 均为 `top.aico.wxcacheviewer.dev`，release 包均为 `top.aico.wxcacheviewer`。
- debug 合并 manifest 的 FileProvider authority 为 `top.aico.wxcacheviewer.dev.fileprovider`。
- 八个包的 `zipalign -c -P 16` 校验全部通过，16KB 页对齐无退化。
- 真机（MT MCP）安装验证：先装 release v0.1.0，再装 debug 包，两者同时存在，安装未报签名冲突。
- 应用名覆盖后重新验证：`aapt2 dump badging` 显示 debug 标签「微信缓存查看器（开发版）」、release 标签「微信缓存查看器」；真机已安装列表同时显示两个不同名称的包，桌面可区分。
