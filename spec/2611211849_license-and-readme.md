# 添加开源协议与 README

## 目标
让仓库具备对外可读的基本说明与授权条款：新增 GPL-3.0 协议文件与中文 README。

## 方案要点
- `LICENSE` 使用标准 GPL-3.0 全文，版权人取仓库作者（`git config user.name`），年份 2026。
- `README.md` 用中文，按「是什么 → 功能 → 运行要求 → 构建 → 使用 → 原理 → 结构 → 限制 → 免责 → 许可」组织。
- 功能描述与当前实现逐项对齐，不写未实现的能力；播放器、多选、昵称识别等按实际行为描述。
- 构建章节说明 `gradle.properties` 中 `org.gradle.java.home` 为开发机绝对路径，他人需自行修改或删除。
- 原理章节只写已确证结论：`hash = md5("mm" + uin)`、昵称两级回退、命名空间升级、`su` 命令白名单。
- 隐私声明以 `AndroidManifest.xml` 实际未申请网络权限为依据。
- 免责声明明确仅限本人已 root 设备、删除不可恢复、遵守当地法律与平台条款。
- 不写入任何真实账号信息（uin、hash、wxid、昵称、手机号等）。

## 完成标准
- `LICENSE`、`README.md` 存在于仓库根目录，换行统一 LF。
- README 中的命令与路径与 `app/build.gradle.kts`、`.gitignore` 实际一致。
