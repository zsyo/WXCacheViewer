# GitHub Actions 签名发布流水线

## 背景

此前 `assembleRelease` 只产出未签名 APK，无法直接安装发布。目标是在推送 tag 时由 GitHub Actions 自动构建签名包、校验后发布到 GitHub Release；签名材料不进仓库。

## 签名配置

`app/build.gradle.kts` 的签名读取分两级，环境变量优先、本地 properties 兜底：

- 环境变量：`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，供 CI 使用；
- 根目录 `keystore.properties`：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`，供本地构建使用，已加入 `.gitignore`；
- 密钥库为 PKCS12 格式，整个容器（含私钥）由单一口令保护：`keytool -genkeypair -storetype PKCS12` 不传 `-keypass` 时只询问一次口令，key 密码自动取 store 密码。因此 properties 里的 `storePassword` 与 `keyPassword` 必须填同一个值，GitHub 侧的 `KEYSTORE_PASSWORD` 与 `KEY_PASSWORD` 也填同一个值，填不同会签名失败；
- 两者都缺失时不创建 `release` signingConfig，`assembleRelease` 退回未签名构建，这样 fork 提 PR 或别人 clone 后只跑 debug 都不会因缺密钥失败。

版本号同样从环境注入：`VERSION_NAME` 默认 `0.1`，`VERSION_CODE` 默认 `1`。CI 分别用 tag 名和 `github.run_number`。

## 工作流

`.github/workflows/release.yml`：

- 触发：`push` 的 `v*` tag，以及 `workflow_dispatch` 手动验证；`permissions: contents: write`；同 tag 不取消在跑的任务。
- 步骤顺序：checkout → setup-java 17 → setup-android（单行空格分隔安装 `platform-tools platforms;android-35 build-tools;35.0.0 ndk;28.2.13676358 cmake;3.22.1`）→ setup-gradle → 删除仓库 `gradle.properties` 里指向开发机 Windows JDK 的 `org.gradle.java.home`（Linux runner 上不存在该路径会让 Gradle 直接报错）→ 算版本号 → 读取 tag message → 解码 keystore → 构建 → 校验签名与页对齐 → 改产物名 → 上传 artifact → 发布 Release。
- `setup-android` 的 `packages` 参数按空格切分，写成多行会把换行符带进包名导致安装失败，所以必须是单行。
- 发布步骤只在 tag 触发时执行；手动触发时版本号用 `dev-<短 sha>`，不会污染 Release。
- 校验：`apksigner verify --print-certs` 确认签名，`zipalign -c -P 16 4` 确认原生库仍是 16 KB 页对齐（见 `2611212150_16kb-page-align.md`）。
- 构建命令用 `bash ./gradlew` 而不是 `./gradlew`：Windows 上开发时 gradlew 工作区有执行位但 Git 索引里是 `100644`，clone 到 Linux runner 上会 `Permission denied`（exit 126）。已用 `git update-index --chmod=+x gradlew` 把索引改成 `100755`，同时用 `bash` 调用做双保险。

## Release 正文与预发布

借鉴 `G:\code\rust\NetOwl\.github\workflows\release.yml`（`OmniKit` 的 CI 只有 check + wasm，没有发布逻辑，不具参考价值）：

- annotated tag（`git tag -a -m "..."`）的 message 作为 Release 正文上半，其下叠加 `generate_release_notes: true` 按提交自动生成的变更列表（含 Full Changelog）；轻量 tag 没有 message 时只有自动生成部分。
- 读取方式：`git fetch --depth=1 origin "+refs/tags/${tag}:refs/tags/${tag}"` 浅拉 tag 对象，用 `git cat-file -t` 判断是不是 annotated tag（轻量 tag 的 `%(contents)` 会退化成提交说明，不能当发布说明），message 写到 `release-notes.md` 文件，通过 `body_path` 传给 action——比塞进 `GITHUB_OUTPUT` 多行变量更稳妥，不会遇到 here-doc 分隔符冲突。
- 版本号含 `-` 预发布段（如 `v0.2.0-beta.1`）时 Release 标记 `prerelease: true`。
- 本次创建了带 message 的 annotated tag `v0.1.0`，由用户手动推送。

## Action 版本

早期参考过 `G:\code\rust\OmniKit\.github\workflows\ci.yml` 的「测试作为发布前置」结构，后确认该仓库是半成品、无发布逻辑，实际以 NetOwl 的 tag message 叠加与预发布判定为准。action 统一用当前最新版主版本：checkout@v7、setup-java@v6、setup-android@v4、setup-gradle@v6、upload-artifact@v7、softprops/action-gh-release@v3。已通过 GitHub API 确认这些 tag 都存在。

## 密钥交付

本地生成的 PKCS12 密钥库 `release.keystore`（4096 位 RSA，有效期 10000 天，alias `wxcacheviewer`）和含密码的 `keystore.properties` 都不提交，用户后续自行移走。GitHub 侧需要四个仓库 Secret：`KEYSTORE_B64`（密钥库 base64 单行）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。构建时密钥库解码到 `$RUNNER_TEMP`，不落在工作区，也不会被 artifact 带走。

README 的「GitHub Actions 自动构建与发布」一节写了从生成密钥库、base64、添加 Secret 到推 tag 取产物的完整步骤。

## 验证

- `./gradlew :app:assembleRelease` 本地用 `keystore.properties` 成功产出签名包；`apksigner verify --print-certs` 显示签名者 `CN=zsyo, O=WXCacheViewer, C=CN`；`zipalign -c -P 16 4` 通过。
- 工作流 YAML 无制表符、引号配对；所有 action 版本 tag 经 GitHub API 返回 200。
- 因本地无 YAML 解析库，未做严格语法解析；首次在 GitHub 上手动触发 `workflow_dispatch` 即可验证。
