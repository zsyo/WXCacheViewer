# 播放页：控制层让开状态栏与手势条

## 问题

下载后真机反馈（视频号与聊天视频都走同一个 `PlayerScreen`）：

- 顶栏文件名压在系统状态栏上，时间、信号图标直接盖住文字；
- 底部控制条贴着屏幕下边缘，拖进度条时容易触发系统的返回 / 回桌面手势。

## 原因

应用 `targetSdk = 35`，Android 15 起对这类应用**强制边到边**：窗口内容铺满整屏，
状态栏与手势条变成浮在内容之上的半透明层。`PlayerScreen` 的上下两条控制栏只写了固定
`padding`，没有任何窗口 inset 补偿，于是坐标算在屏幕原点，正好被系统栏压住。

对比：`ImageViewerScreen` 早就用 `statusBarsPadding()` / `navigationBarsPadding()` 让开了，
播放页是唯一漏掉的整屏页面，所以只有它出问题。

## 方案

视频画面保持铺满整屏（播放器的正常观感），只让**控制层**让开安全区：

- 顶栏：`WindowInsets.safeDrawing.only(Top + Horizontal)`
- 底栏：`WindowInsets.safeDrawing.only(Bottom + Horizontal)`

用 `safeDrawing` 而不是 `statusBars` / `navigationBars`：应用没有锁竖屏，横屏播放时
挖孔在侧边，`statusBars` 不含 `displayCutout`，只有 `safeDrawing` 能把左右也退开。

`windowInsetsPadding` 放在 `background()` **之后**、内容 `padding` **之前**：
背景照旧铺到屏幕边缘（顶部渐变、底部半透明黑不断层），只有内容整体内移。

底部内容 `padding` 的 `bottom` 由 4dp 调成 8dp，让进度条与手势条之间多留一点空白，
横向拖动时更不容易被系统的边缘手势抢走。

## 验证

- `lintDebug` 无告警，四 ABI `assembleDebug` 通过，单测 151 条全绿。
- 真机：视频号与聊天视频各开一个，顶栏在状态栏下方、进度条离开手势条；横屏再确认一次。
