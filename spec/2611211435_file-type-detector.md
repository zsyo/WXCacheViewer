# 文件头类型嗅探

## 目标
不依赖文件名扩展名，通过文件头部魔数判断缓存文件的真实类型。

## 方案要点
- `FileTypeDetector` 为纯 Kotlin 对象，提供 `detect(hex)` 与 `detect(bytes)`。
- 识别 MP4（`ftyp` at offset 4）、JPEG、PNG、GIF、WEBP；其余返回 UNKNOWN。
- 头长度不足或十六进制非法时返回 UNKNOWN，不抛异常。
- 领域模型 `Account`、`CacheFile`、`FileType` 放在 `domain/model`。

## 完成标准
`./gradlew testDebugUnitTest --tests "*FileTypeDetectorTest"` 通过。
