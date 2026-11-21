# 功能：自动识别账号昵称

## 目标

账号列表与顶栏在用户没有填写备注时，自动显示该账号的昵称，而不是只有一串 32 位 hash。
优先级：**手动备注 > 自动识别昵称 > 短 hash**。

## 取名来源（两级）

1. `MicroMsg/<hash>/FinderAccounts_01.db`（明文 SQLite）的 `FinderAccount.nickname`
   - 库跟着账号目录走，每个账号都能独立读到自己的视频号昵称；
   - 用 root 把 `.db`、`-wal`、`-shm` 复制到应用私有目录后用 `SQLiteDatabase` 只读打开，
     缺 WAL 可能读到空表，所以三件套一起复制。
2. `shared_prefs/com.tencent.mm_preferences.xml` 的 `last_login_nick_name`（兜底）
   - 只对应「最后登录账号」，必须用 `last_login_uin` 计算出 `md5("mm"+uin)` 与目录名相等才采用；
   - 只读取 `last_login_nick_name`、`last_login_uin` 两个字段（grep 定点取值），
     手机号、邮箱等其余字段既不解析也不记录。

任一步失败都静默回退到下一级，不影响账号列表的显示。

## 实现要点

- `domain/UinHash`：`md5("mm" + uin)` 纯函数，只接受纯数字 uin，供兜底校验使用。
- `data/root/WxPaths`
  - `copyFinderAccountDbCommand(hash, destinationDir)`：校验 hash 后复制数据库三件套，
    逐个文件 `chmod 0666`（复制件属主是 root，应用需要可读写以配合 WAL）；源文件不存在时输出 `MISSING`。
  - `readLoginPrefsCommand()`：两条 `grep -o`，只摘昵称与 uin。
- `data/repository/AccountNameRepository`
  - 临时目录 `cache/account_name_tmp/<hash>` **由应用自己创建**：root 创建的目录属主是 root，
    SQLite 无法在其中创建 `-shm`；若用 `chmod -R 0666` 还会抹掉目录的执行位，导致根本进不去。
  - 读取完成后立即删除临时目录。
  - `cleanName`：解码 XML 转义、压掉换行、限制 32 字。
- `AccountUiState.autoNames`：hash → 昵称。
- `AccountViewModel.refresh`：先把账号列表与备注推给界面，昵称解析完成后再回填，避免首屏卡住。
- 界面：选择账号页、切换账号弹窗、相册顶栏统一使用「备注为空则显示昵称」，
  有名称时把 hash 降为副标题；备注输入框用昵称作 placeholder，表示不填就走默认值。

## 验证

真机日志确认主路径生效（数值为示例占位）：

```
[account] discovered=1 [(ffdef473a3a56cbf6d38456a41f52f46, true)]
[shell] exit=0 out=name="last_login_nick_name">示例昵称 ⏎ name="last_login_uin">1000000001
[shell] exit=0 out=OK            （FinderAccounts_01.db 复制成功）
[account] names=1/1 fromDb=1 fromLogin=0 loginVerified=true
```

`fromDb=1` 说明昵称来自账号目录内的数据库；临时目录读取后已清空。
