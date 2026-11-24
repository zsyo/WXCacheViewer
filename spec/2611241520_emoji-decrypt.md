# 表情：读取加密表情

## 需求

表情目录 `MicroMsg/<hash>/emoji` 里有一半左右的文件是裸 32 位 md5（无扩展名），之前判定为「加密数据、无法解码」
而不展示。表情页因此只能看到带 `_gif` / `_cover` / `_thumb` 后缀的明文文件，没有明文兄弟的表情整张丢失。

现在要把裸 md5 也读出来：能解的展示、能导出的导出，确实解不了的（微信私有 wxgf）明确不列。

## 实测结论

在一台 Android 微信 8.x 真机上取样本验证（GIF / PNG / wxgf 三类都覆盖到）：

1. **只有前 1024 字节被加密**。同一表情的裸 md5 与 `<md5>_gif` 相比，前 1024 字节不同之外，其余字节逐一相同，
   总长度也相同。因此这不是整文件加密，而是「头部密文 + 尾部明文」。
2. **算法是 AES-128-ECB、无填充**。解密前 1024 字节后拼接尾部原文，得到的整份内容 md5 正好等于文件名
   （GIF、PNG 样本均验证通过），说明密钥与模式都正确。
3. **密钥由 uin 推导**：`md5(十进制 uin)` 的十六进制串取前 16 个字符，直接作为 16 字节 ASCII 密钥。
   网上流传的 `md5(imei)` 在本机解不出来 —— 可能是版本差异，实测以 `md5(uin)` 为准。
4. 解密后的内容有三种：GIF / PNG 能正常展示，第三种以 `wxgf` 开头（12 字节头 + 裸 H.265 流），
   系统解码器打不开，本次不做解码。

样本分布上，裸 md5 里 wxgf 占比不低（抽样约 3/5），因此「能解但不展示」的比例需要接受。

## 密钥来源与账号校验

uin 存在 `shared_prefs/com.tencent.mm_preferences.xml` 的 `last_login_uin` 里，root 可读。
只认「最后登录账号」的 uin，并且必须满足 `md5("mm" + uin) == 账号目录名`（`UinHash`）才用于该账号：

- 校验通过：该账号的加密表情参与展示与导出。
- 校验不过（多账号、切过账号、prefs 读不到）：该账号退回只展示明文表情。
  拿别的账号的密钥去解，结果只会是一屏乱码，宁可不展示。

相关实现：`data/emoji/EmojiKeyStore.kt`（读 uin、校验、缓存）、`WxPaths.readLoginUinCommand()`（只 grep uin 一个字段）。

## 列表侧

- `SourceRegistry.EMOJI` 的白名单扩成 `^[0-9a-f]{32}(?:_(?:gif|cover|thumb))?$`，glob 用 `*`，靠正则兜底。
- 裸 md5 与 `<md5>_gif` 解密后字节完全一致，因此 shell 在计数前就跳过「已有 `_gif` 兄弟」的裸文件
  （`CacheSource.skipSiblingSuffix = "_gif"`），避免同一张表情出现两遍，也保证分页游标对得上。
- 列目录时 shell 已经读了文件头 32 字节。ECB 按分组独立解密，这 32 字节足够解出真实类型，
  所以在解析列表行时直接判定 GIF / PNG / JPEG，不必为每个文件再 fork 一次去读文件。
- 判定为 wxgf 或解不出类型的行直接丢弃；整页都是加密文件时按「正常空页」处理，不报「格式异常」。
- 拿不到密钥时同样丢弃这类行，界面不会有打不开的卡片。

## 解密与导出

`MediaPreparer` 与 `EmojiPreparer` 都走「root 复制只读原件 → 应用读出、解开前 1KB → 写成自己的文件」：

- 不在原件上就地改写。root 复制出来的文件属主是 root、`0644` 无写权限；放宽成 `0666` 也可能被 SELinux 标签挡住，
  让应用自己创建产物最稳（属主与标签都正确）。
- 解密产物文件名带 `.dec` 标记，避免和「原件直通」的缓存名撞车；长度与原文件一致，缓存命中判据不变。
- 导出按解密后识别出的真实类型补扩展名（GIF/PNG/JPEG），导出的就是能直接打开的文件。

## 失败与日志

- `[emoji] login uin resolved=...`：uin 是否读到。
- `[emoji] page emoji@N kept=X/Y hasKey=...`：本页保留了多少、有没有密钥（成片丢弃时看这行）。
- `[media] decrypt failed ...` / `[emoji] decrypt failed ...`：密钥不对或 IO 失败，卡片会显示占位而不是乱码。

## 测试

- `EmojiCryptoTest`：密钥派生格式、非数字 uin 拒绝、分组解密往返、头部 hex 解密、wxgf 识别。
- `CacheRepositoryTest`：密文头部经解密后识别出 GIF 并标记 `encrypted`；无密钥时整页跳过且不报错。
- `SourceRegistryTest` / `WxPathsTest`：裸 md5 进入白名单、`_gif` 兄弟跳过片段只出现在表情源。
