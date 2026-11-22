# 微信缓存查看器（WXCacheViewer）

一个用于在**已 root 的 Android 设备**上浏览、播放、导出和清理微信缓存的工具型应用，支持视频号视频、聊天视频、表情、聊天图片/朋友圈、语音、聊天文件六类缓存。

这些缓存都躺在应用私有目录里，文件名往往没有扩展名，普通文件管理器既看不到、也打不开。本应用通过 root 读取这些目录，按文件头识别类型并生成缩略图，提供相册式浏览、内置播放器与图片预览，同时支持把缓存导出成正常的 MP4、GIF、JPG/PNG、MP3 文件。

> 仅适用于查看**本机、本人账号**的缓存数据。详见文末「免责声明」。

## 功能

**账号**

- 自动扫描 `cache/` 与 `MicroMsg/` 下的 32 位账号目录，并标记哪些账号有视频缓存
- 首次启动选择账号，可填写中文备注；备注为空的显示自动识别的昵称
- 昵称识别优先级：手动备注 > 账号目录内数据库中的视频号昵称 > 最后一次登录的微信昵称 > 8 位短 hash
- 顶栏显示当前账号，点击可快速切换，切换后按原排序重新加载

**缓存类型（顶部标签页切换）**

| 标签页 | 目录 | 说明 |
|---|---|---|
| 视频号 | `cache/<hash>/finder/video` | 无扩展名 MP4，按文件头识别 |
| 聊天视频 | `MicroMsg/<hash>/video` | 标准 MP4，优先用同目录同名 jpg 作缩略图 |
| 表情 | `MicroMsg/<hash>/emoji` | 只收明文的 `_gif` 动图与 `_cover`/`_thumb` 封面；裸 md5 的加密文件不展示 |
| 图片 | `MicroMsg/<hash>/image2` 与 `cache/<hash>/sns` | 聊天图片缩略图、朋友圈缓存，递归子目录，只读 |
| 语音 | `MicroMsg/<hash>/voice2` | 后缀是 `.amr`，实际是 SILK v3，解码后在列表里直接播放，只读 |
| 文件 | `MicroMsg/<hash>/attachment` | 聊天里收发的文档、压缩包、安装包、音视频等，只读 |

**浏览**

- 网格（1 列 / 2 列）与列表两种视图，选择结果持久化
- 卡片显示类型、文件大小与修改时间，视频显示首帧缩略图，图片与表情显示预览
- 排序菜单：按文件大小或修改时间，正序或倒序，默认修改时间倒序；切换排序后自动回到顶部
- 下拉刷新
- 文件类型按文件头魔数识别，不依赖扩展名；聊天文件按原始扩展名判断（docx/xlsx/pptx 与 zip 魔数相同，只看魔数会误判）
- 所有标签页分页加载：滚动到接近底部时自动取下一页，底部显示加载进度。列表按页切片，
  首屏耗时与缓存总量无关（视频号、聊天视频、表情每页 60 个，聊天文件每页 40 个，图片、朋友圈与语音每页一个一级子目录）

**语音**

- 语音页固定单列列表：左侧是播放/暂停按钮，右侧是时间与体积，播放中在该行底部显示进度
- 点击即播，不需要离开列表；解码期间按钮位置显示环形进度
- 微信语音是 `0x02` + `#!SILK_V3` 的私有封装，应用先剥掉私有头再用 SILK 解码器转成 PCM 播放
- 会按「帧数 × 20ms」校验解码时长，采样率对不上（老版本微信录的是 16kHz）时自动换 16kHz 重解
- 导出统一转成 mp3，落到导出目录的「语音」子目录，外部播放器可直接播放

**聊天文件**

- 文件页固定单列列表：左侧是扩展名徽标（PDF / DOCX / ZIP / APK…），右侧是文件名与大小/时间
- 图片类文件点开走全屏预览，视频走内置播放器，文档与压缩包交给系统「打开方式」（FileProvider 只读分享）
- 导出保留原始文件名，重名时自动加 `(1)`、`(2)` 后缀，不覆盖已有文件

**图片预览**

- 点击图片或表情进入全屏预览：左右滑动翻页、动图自动播放、显示类型/大小/时间
- 顶栏保存按钮直接把当前图片导出到导出目录的「图片」子目录

**播放**

- 基于 Media3 ExoPlayer 的内置播放器，播放前把文件复制到应用私有目录并补上 `.mp4` 扩展名
- 播放/暂停、进度条拖动、左右长按快退快进
- 倍速切换（按钮固定宽度，避免进度条抖动）
- 应用内独立音量（不改动系统媒体音量），垂直滑块面板，喇叭按钮在静音与上次音量之间切换
- 播放结束后点击播放按钮从头重播，也支持播放中暂停后继续

**批量操作**

- 长按任意文件进入多选模式，并自动选中该项；可继续点选或取消其它项
- 底部悬浮工具条：全选 / 全不选、反选、导出、删除、退出选择
- 全不选不会退出多选模式
- 批量导出、删除时工具条原地显示环形进度与「正在导出 12/100」计数，结束后汇总成功与失败数量
- 所有导出都落在设置里选定的统一目录下：视频进「视频」、图片与表情进「图片」、语音进「语音」、聊天文件进「文件」；没设置过时根目录是 `Download/WXCacheViewer`，导出完成后按各自 MIME 通知媒体库扫描
- 删除前二次确认，删除的是微信缓存目录中的原始文件，不可恢复；聊天图片、朋友圈、语音与聊天文件为只读源，不提供删除

**设置**

- 顶栏齿轮进入设置页，返回时保留原来的标签页、列表与滚动位置
- 导出目录：用系统文件选择器挑一个目录，导出时在其中按类型新建「视频 / 图片 / 语音 / 文件」子目录，文件集中在一处便于管理；可一键恢复默认目录 `Download/WXCacheViewer`
- 缓存管理：统计应用私有缓存目录的占用，一键清理复制出来的媒体文件与语音解码产物；只动应用自己的 `cacheDir`，不碰微信的缓存
- 关于：应用名称与版本、开源仓库与开发者主页（点击用系统浏览器打开）、开源许可、隐私说明与免责声明

## 运行要求

| 项 | 要求 |
|---|---|
| 系统 | Android 7.0（API 24）及以上 |
| 权限 | **必须 root**，已安装 Magisk / KernelSU 等可用的 `su`，并在 root 管理器中授权本应用 |
| 微信 | 已登录并产生过视频号缓存 |

Android 16 及以上系统对应用数据目录做了更强的隔离，直接在应用进程内执行 `su -c` 会继承应用的挂载命名空间，看不到微信的私有目录。应用会自动探测并逐级升级执行方式（`su -c` → `su -mm -c` → `nsenter -t 1 -m`），无需手动配置。

## 构建

环境：

- JDK 21（运行 Gradle；源码编译目标仍是 Java 17）
- Android SDK Platform 37、Build-Tools 37.0.0（Compose 1.12 / Coil 3.6 要求 compileSdk 37）

```bash
# 调试包（按 ABI 拆成多个）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-<abi>-debug.apk

# 单元测试与 lint
./gradlew testDebugUnitTest lintDebug

# 发布包（按 ABI 拆成多个，开启 R8 混淆与资源压缩）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-<abi>-release.apk
```

构建会产出四个 ABI 包，每个只含一套原生库（SILK 解码器与 LAME），体积比原先的全 ABI 单包小：

| 文件后缀 | 适用设备 |
| --- | --- |
| `arm64-v8a` | 绝大多数 64 位 ARM 手机（推荐） |
| `armeabi-v7a` | 较旧的 32 位 ARM 手机 |
| `x86` | 32 位 x86 模拟器/设备 |
| `x86_64` | 64 位 x86 模拟器/设备 |

四个包的 `versionCode` 按 ABI 分段（如 `2xxxx` 为 arm64），互不冲突；GitHub Release 会把四个包一并作为附件发布，按设备架构下载即可。

`gradle.properties` 中有一行 `org.gradle.java.home`，指向开发机上的 JDK 21 绝对路径。如果你本机路径不同，请改成自己的路径或直接删除该行并设置 `JAVA_HOME`。同理，`local.properties` 里的 `sdk.dir` 需要指向你自己的 SDK 位置（该文件已被 `.gitignore` 排除）。CI 会自动删掉 `org.gradle.java.home` 这一行，无需为 Linux runner 手动改仓库文件。

本地要打**签名**发布包，在根目录建一个 `keystore.properties`（已被 `.gitignore` 排除，不会进仓库）：

```properties
storeFile=release.keystore
storePassword=你的密码
keyAlias=wxcacheviewer
keyPassword=你的密码
```

`storePassword` 与 `keyPassword` 必须填**同一个值**：密钥库是 PKCS12 格式，整个容器（含私钥）由单一口令保护，`keytool` 生成时只问一次口令，key 密码自动取 store 密码。如果两项填不同值，签名会报密码错误。

`app/build.gradle.kts` 会优先读环境变量 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，都没有时才读这个文件；两者都缺失就退回未签名构建，所以别人 clone 下来只跑 debug 或 fork 提 PR 都不需要密钥。

## GitHub Actions 自动构建与发布

推送 `v` 开头的 tag（如 `v0.1.0`）会触发 `.github/workflows/release.yml`：自动装 JDK 21 与 NDK 28.2.13676358 / CMake 3.22.1，跑单测与 lint，用密钥签名后产出四个 ABI 包，逐个校验签名与 16 KB 页对齐，最后把四个 APK 一并附到对应的 GitHub Release 上。也可在 Actions 页面手动触发来验证流水线，手动触发不发布 Release。

首次接入按下面步骤做一次：

1. **准备密钥库**。用 `keytool` 生成一次，长期保存（丢失后无法再给已安装用户升级，只能让用户卸载重装）：

   ```bash
   keytool -genkeypair -v -keystore release.keystore -storetype PKCS12 \
     -alias wxcacheviewer -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=zsyo, O=WXCacheViewer, C=CN"
   ```

   PKCS12 只问一次「密钥库口令」（再输一次确认），不会再单独问 key 密码——私钥条目自动沿用 store 口令。

   文件不要提交，也不要放进仓库目录。

2. **把密钥库编码成文本**。GitHub Secrets 只存文本，二进制密钥库要先 base64：

   ```bash
   base64 -w0 release.keystore > keystore.b64   # Linux
   # PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))
   ```

3. **添加四个 Secrets**。在仓库 Settings → Secrets and variables → Actions → New repository secret 里逐个添加：

   | Secret | 值 |
   | --- | --- |
   | `KEYSTORE_B64` | 上一步得到的 base64 字符串 |
   | `KEYSTORE_PASSWORD` | 生成密钥库时设置的口令 |
   | `KEY_ALIAS` | `wxcacheviewer` |
   | `KEY_PASSWORD` | 与 `KEYSTORE_PASSWORD` 相同（PKCS12 单一口令） |

4. **推 tag 触发**：

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

   `versionName` 取 tag 去掉 `v` 后的部分（`v0.1.0` → `0.1.0`），`versionCode` 取 GitHub 的 run number，保证每次发版都递增。`KEYSTORE_B64` 在构建时解码到 runner 的临时目录，不会进入仓库或产物。
   建议用 annotated tag（`git tag -a v0.1.0 -m "版本说明"`）写发布说明：tag 的 message 会作为 Release 正文上半，下面再叠加 GitHub 按提交自动生成的变更列表；轻量 tag 没有 message 时只有自动生成部分。tag 版本号含 `-` 预发布段（如 `v0.2.0-beta.1`）时 Release 会标记为预发布。

5. **取产物**。每个 tag 的四个 ABI 包在两个地方：Actions 运行页 Artifacts（保留 14 天）和该 tag 对应的 GitHub Release 附件（长期保留）。每个包在上传前都已通过 `apksigner verify` 与 `zipalign -c -P 16` 校验。

注意：release 签名与开发时装的 debug 包签名不同，覆盖安装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先卸载旧包（会清掉应用私有数据，不影响微信缓存本身）。另外按 GPL-3.0，发布二进制时仓库需保持可访问以提供对应源码。

## 使用说明

1. 安装并打开应用，首次启动会请求 root 权限，请允许。
2. 在账号列表中选择要查看的账号；建议填一个备注，之后顶栏与切换弹窗都会显示它。
3. 进入相册后可切换视图、调整排序、下拉刷新；点击视频进入播放页。
4. 需要留存或清理时，长按进入多选，用底部工具条批量导出或删除。

如果启动后提示「未发现微信账号」，按顺序排查：root 是否真的授予了本应用、微信是否登录过并产生过视频号缓存、账号目录是否位于 `/data/user/0/com.tencent.mm/cache/` 下。

## 工作原理

**root 命令通道**

所有文件操作都不直接由应用进程执行，而是拼成 shell 命令交给 `su` 执行，输出通过标准输出回传后解析。为降低注入风险：

- 账号目录名必须匹配 `^[0-9a-f]{32}$`，缓存文件名必须匹配 `finder_video(_xWT<数字>)?_[0-9a-f]{32}`
- 所有路径片段先用单引号包裹并转义内部单引号
- 文件列表按页输出六个制表符分隔字段（名称 / 大小 / mtime / 文件头十六进制 / 相对路径 / 是否有同名 jpg 首帧），首屏只取一页，避免上万文件的目录一次扫描

**账号与昵称**

账号目录名无法从 hash 反推账号信息，但可以通过 `uin` 正向计算：

```
hash = md5("mm" + uin)
```

昵称的取法分两级：优先复制账号目录下的 `FinderAccounts_01.db`（明文 SQLite，连同 `-wal`、`-shm` 一起复制后只读查询 `FinderAccount.nickname`），读不到时退回 `shared_prefs` 中的 `last_login_nick_name`，且必须满足 `md5("mm" + last_login_uin)` 等于该目录名才采用，避免把最后一次登录的昵称错标到别的账号上。prefs 中只读取昵称与 uin 两个字段，手机号、邮箱等字段完全不解析。

**缩略图与播放**

缩略图与播放都用「复制到应用私有目录再读取」的方式：root 把文件复制出来、补上扩展名并设置可读权限，应用再用 `MediaMetadataRetriever` 取首帧、用 ExoPlayer 播放，图片则交给 Coil 加载（网格只解首帧，预览页才播动图）。缩略图有内存缓存，播放缓存只保留最近 3 个文件。聊天文件同样先复制到 `cache/media`，再由 `FileProvider` 以 `content://` 只读分享给系统应用打开。

**语音解码**

`voice2` 里的 `.amr` 与 AMR 无关，是 SILK v3，而且微信在标准 `#!SILK_V3` 头前面插了一个 `0x02` 私有字节、又去掉了结尾的 `0xFF 0xFF`，标准解码器读到首字节是 `0x02` 会直接帧同步失败。因此解码前要先剥掉这一个字节，再交给 SILK v3 定点解码器转成 wav 或 mp3。

解码器与 LAME 的 C 源码放在 `app/src/main/cpp`，构建时由 CMake 一起编译，不用第三方预编译 so。原因不只是减少一个依赖：现成的 SILK 库 ELF 段只对齐到 4KB，在 16KB 页设备上会被系统判定成「页面大小兼容模式」，每次启动都弹兼容性对话框；自己编译可以把 LOAD 段对齐到 16KB（NDK r28 默认如此，链接参数里也显式写了 `-Wl,-z,max-page-size=16384`）。

微信早期版本录音是 16kHz、新版是 24kHz，而 SILK 头里没有记录采样率。应用按「帧数 × 20ms」算出期望时长，用它校验解码结果的采样率，偏差超过 15% 就换 16kHz 重解一次，并把最终采样率写在 `.rate` 旁注文件里，命中缓存时不必重复校验。解码器是原生库且不保证线程安全，所有解码串行执行，失败统一转成界面提示。

**缓存源与安全**

每类缓存都在 `domain/source/SourceRegistry` 里声明为一条 `CacheSource`（根目录、相对目录、是否递归、文件名白名单、导出策略）。所有路径先按源的正则匹配，再做段级校验（禁止绝对路径、`..`、反斜杠、超深目录），拼接后还要求结果以源目录前缀开头；删除只对白名单内的允许删除源开放。

**隐私**

应用不申请网络权限，不联网、不上报任何数据；日志只写在本应用私有目录 `files/logs/`，可通过应用信息页清除。

## 项目结构

```
app/src/main/java/top/aico/wxcacheviewer/
├── data/
│   ├── root/            root 命令通道：路径与命令拼装、命名空间升级、账号发现解析、诊断
│   ├── repository/      缓存文件读取、账号昵称解析、偏好设置
│   ├── thumbnail/       视频首帧与同名 jpg 缩略图
│   ├── media/           图片复制到私有目录，供 Coil 加载
│   ├── image/           两个 Coil 实例（静态首帧 / 动图）
│   ├── playback/        播放前的文件复制
│   ├── audio/           语音：复制、剥私有头、SILK 解码与播放
│   └── log/             文件日志
├── domain/              纯 Kotlin 逻辑：缓存源定义与白名单、文件类型识别、排序、uin→hash
├── viewmodel/           账号与相册的 UI 状态
└── ui/                  Compose 界面：账号选择、相册、文件列表、播放器、图片预览
app/src/main/cpp/        SILK v3 解码器与 LAME 的 C 源码、JNI 入口与 CMake 脚本
art/                     应用图标的原始设计稿（PNG），据此生成各密度 mipmap
spec/                    每个功能的规格文档
```

技术栈：Kotlin 2.4.21（AGP 9 内置 Kotlin 编译）、Jetpack Compose（BOM 2026.09.00）、Material 3、Media3 ExoPlayer 1.11.1、Coil 3.6.3、SILK v3 定点解码器与 LAME（源码随模块编译）、AGP 9.5.0、Gradle 9.9.2。

## 已知限制

- 表情目录里的裸 32 位 md5 文件是加密数据，无法解码，因此表情页只展示动图与封面。
- 聊天图片目录里通常只剩缩略图（原图会被微信清理），所以看到的画质有限。
- 语音只列出 `voice2` 下 `msg_amr_` 前缀的 `.amr`；被微信清理过的语音目录会是空的，应用无法凭空恢复。
- 自动识别到的是**视频号昵称**，与微信昵称可能不同（用户单独改过视频号名时）。
- `EnMicroMsg.db`、`WxFileIndex.db`、`account.bin` 等文件为加密格式，本应用不解密也不读取。
- 首次进入账号列表时需要为每个账号复制一次资料库以读取昵称，账号多时会有短暂延迟；昵称解析失败不影响浏览。
- 依赖设备上的 `su` 实现，不同 root 方案（Magisk、KernelSU、APatch）行为可能有差异，已在 KernelSU 设备上验证。

## 免责声明

本项目仅供学习 Android 存储机制、root 命令通道与 Compose 界面开发之用，面向**自己拥有并已 root 的设备**，用于查看与备份本人账号的缓存文件。

- 请勿用于他人设备，或用于侵犯他人隐私、绕过平台保护措施等用途。
- 删除操作会直接移除微信缓存目录中的原始文件且无法恢复，请谨慎使用。
- 使用本软件产生的一切后果由使用者自行承担，作者不对数据丢失或账号异常承担责任。
- 请遵守当地法律法规以及微信的服务条款。

## 许可证

本项目以 [GNU General Public License v3.0](LICENSE)（GPL-3.0）发布，Copyright (C) 2026 zephyr。

你可以自由使用、修改和再分发本程序，但须遵守 GPL-3.0 的条款：分发二进制时要一并提供完整源码，衍生作品继续以同一协议授权，且不得附加额外限制。

随源码编译的第三方组件各有自己的协议，均与本项目所选协议兼容：

- SILK v3 解码器：SILK SDK License（Skype Limited，BSD 三条款式，与 GPL 兼容）
- LAME：LGPL 2.0 及以后版本（GPL 明确允许升格为 GPL 使用）
- AndroidX、Jetpack Compose、Media3、Coil：Apache 2.0

