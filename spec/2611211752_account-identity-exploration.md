# 探索：账号目录 hash 与微信昵称/微信号的对应关系

> 本文只记录在真机（KernelSU，Android 17）上的只读探索结论，尚未实现到代码。
> 文中所有取值均为**示例占位**，不代表任何真实账号。

## 一、已验证的事实

### 1. 目录 hash 的来源

`/data/user/0/com.tencent.mm/cache/<32hex>/finder/video/` 中的 `<32hex>` 与
`/data/user/0/com.tencent.mm/MicroMsg/<32hex>/` 完全一致，且满足：

```
hash = md5("mm" + uin)       （小写十六进制）
```

实测：uin = `1000000001` → `md5("mm1000000001")` = `ffdef473a3a56cbf6d38456a41f52f46`，与目录名逐字匹配。

同时排除了其他可能：`md5(uin)`、`md5(wxid)`、`md5("mm"+wxid)` 均不匹配。

### 2. uin 与微信号来源（当前登录账号）

`shared_prefs/auth_info_key_prefs.xml`：

```xml
<int name="_auth_uin" value="1000000001" />
```

`shared_prefs/system_config_prefs.xml`：

```xml
<int name="default_uin" value="1000000001" />
```

`shared_prefs/com.tencent.mm_preferences.xml`（明文，约 5.5KB）同时给出当前登录账号的身份信息：

```xml
<string name="login_weixin_username">wxid_example00000000</string>
<string name="last_login_uin">1000000001</string>
<string name="last_login_nick_name">示例昵称</string>
<string name="last_login_alias">demo_alias</string>
<string name="last_avatar_path">…/MicroMsg/last_avatar_dir/user_45222f277380e7d2a03852d8080b09f8.png</string>
```

其中 `45222f277380e7d2a03852d8080b09f8` = `md5("wxid_example00000000")`，可交叉验证 wxid。

同一文件里还有 `last_login_bind_mobile`、`last_login_bind_email`、`last_login_bind_qq`
等绑定信息，属于个人敏感数据，本文不记录其取值。

注意：这些字段只描述**最后一次登录**的账号；换号登录后会被覆盖，无法用于其它账号目录。

### 3. 每个账号目录内的昵称来源（多账号场景可用）

`MicroMsg/<hash>/FinderAccounts_01.db` 是**明文 SQLite**（WAL 模式），表 `FinderAccount` 恰好只有一行，即该账号自己的视频号资料：

```
username  = v2_060000231003b20f…@finder   （视频号 ID）
nickname  = 示例昵称                       （与 prefs 中微信昵称一致）
signature = 示例签名
avatarUrl = https://wx.qlogo.cn/finderhead/ver_1/…
```

它是**按账号目录保存**的，因此对多账号设备可逐目录取值，不依赖"最后登录"。

### 4. 已排除的路径

| 来源 | 结论 |
| --- | --- |
| `MicroMsg/<hash>/account.bin`（约 4KB） | 全密文，高熵，无明文 wxid |
| `MicroMsg/<hash>/EnMicroMsg.db` | WCDB 加密，`EnMicroMsg.db.ini` 里只有 `createmd5`，无明钥 |
| `MicroMsg/<hash>/WxFileIndex.db` | 加密（首字节非 `SQLite format 3`） |
| `MicroMsg/<hash>/newuba.db`、`MicroMsgPriority.db` | 加密 |
| `MicroMsg/<hash>/HashInfo.db`（明文） | 表 `HashUserNameMap(UserName, HashUserName, CommHashUserName, HardCodeHashUserName)`，值为 **SHA-256**，是微信内部"哈希用户名"，与本目录 md5 无对应关系 |
| `files/identity_records/<hash>` | 仅 0 字节标记文件，只有 hash |
| `MicroMsg/<hash>/account.mapping` | 32 位 hex = `/sdcard/Android/data/com.tencent.mm/MicroMsg/<hex>/` 的目录名，与该目录的对应关系可用，但不含身份信息 |
| `MicroMsg/` 下的非账号目录 | 例如只含 `EnResDown.db` 的目录，说明账号发现逻辑没有误报 |

### 5. 附带发现

- `files/mmkv/` 下大量文件名以 uin 为前缀（如 `<uin>_BigHeadImg`），可作为"该设备曾用 uin"的旁证。
- `hash` 无法反推 uin（md5 单向、uin 为 32 位整数，穷举不可行）；正确方向是"已知 uin → 算 hash"。

## 二、可行的取名方案（按优先级）

1. **逐账号读取 `MicroMsg/<hash>/FinderAccounts_01.db` 的 `FinderAccount.nickname`**
   - 覆盖所有账号目录，不依赖登录状态；
   - 需要 `su` 复制 `.db`、`-wal`、`-shm` 三个文件到应用私有目录后再用 `SQLiteDatabase` 只读打开（缺 WAL 会读到旧数据或空表）；
   - 局限：拿到的是**视频号昵称**，用户单独改过视频号名称时会与微信昵称不一致。
2. **兜底：`shared_prefs/com.tencent.mm_preferences.xml` 的 `last_login_nick_name`**
   - 仅当 `last_login_uin` 计算出的 md5 与目标 hash 相等时才采用，避免张冠李戴。
3. **再兜底：`last_login_alias` / `login_weixin_username`（wxid）** 作为副标题，同样只在 hash 匹配时使用。
4. 最终仍以用户手动备注为最高优先级（现有逻辑）。

## 三、风险与注意事项

- `FinderAccounts_01.db` 可能正被微信写入，复制三件套时存在不一致风险；读取失败必须静默回退，不能影响账号列表。
- 该数据库只存在于"用过视频号"的账号目录；没有 `finder/video` 缓存的账号也不会出现在列表里，实际影响有限。
- `com.tencent.mm_preferences.xml` 含手机号、邮箱、QQ 等敏感字段，实现时只允许读取昵称/别名/wxid，不要落盘或上报。
- 视频号昵称与微信昵称可能不同，界面上宜标注来源或允许一键"用检测到的昵称填充备注"。

## 四、结论

- hash ↔ uin 的关系已确证：`md5("mm"+uin)`。
- 当前账号的 wxid / 昵称可从明文 prefs 直接取到。
- 多账号场景下，每个账号目录内的明文 `FinderAccounts_01.db` 可提供该账号的昵称，是最可行的通用方案。
- 全过程未修改设备任何文件；分析用的数据库副本已删除。
