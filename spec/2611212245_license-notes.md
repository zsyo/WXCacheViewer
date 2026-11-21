# 补全 LICENSE 说明与第三方组件协议

## 目标
`LICENSE` 已经是 GPL-3.0 全文，但 README 只有一行链接，设置页「开源许可」也没说清各组件各自的协议。本次把授权范围、分发义务与第三方组件协议补全。

## 兼容性核查
- SILK v3 解码器（`app/src/main/cpp/libsilk`，110 个 C 文件）为 Skype Limited 的 SILK SDK License，属 BSD 三条款式条款，与 GPL 兼容，可整体纳入 GPL 作品。
- LAME（`app/src/main/cpp/libmp3lame`）每个文件都写的是「Library General Public License version 2 **or any later version**」，即 LGPL-2.0-or-later，允许升格为 GPL-3.0；不存在只写死 2.0 的文件。
- AndroidX、Compose、Media3、Coil 为 Apache 2.0，与 GPL-3.0 兼容。
- 结论：整体以 GPL-3.0 发布没有冲突，无需替换依赖或改为动态链接。

## 方案要点
- `README.md` 许可证章节展开：版权行、分发义务（提供完整源码、衍生作品同协议、不得附加额外限制）、第三方组件协议列表。
- 设置页「开源许可」文案：SILK 标注 BSD 三条款式，LAME 标注 LGPL 2.0 及以后版本，本项目 GPL-3.0。
- `LICENSE` 保持官方全文逐字不变，版权声明写在 README，不往许可证正文里加头。
- 不在每个源文件里加版权头：GPL 不要求，本项目此前也没有文件头惯例。

## 完成标准
- `LICENSE` 与 GPL-3.0 官方全文逐字一致，仅 LF 换行。
- README 与 `strings.xml` 中的协议表述与 `LICENSE` 一致，不出现互相矛盾的措辞。
- `assembleDebug` 与 `lintDebug` 通过；设置页「开源许可」对话框显示新文案。
