# 播放页：横屏全屏切换

## 需求

内置播放器此前只能竖屏看：竖屏下 16:9 的视频只占屏幕中间一条，上下大片黑边；
而应用本身没有锁方向，用户手动转屏又会让 Activity 重建、播放从头开始。
需要在播放页给一个显式的横屏入口，且转屏不能打断播放。

## 交互

- 按钮位置：**画面右侧垂直居中的悬浮圆钮**，不放在顶栏里——顶栏要留给「返回 + 文件名 + 导出」，
  和图片预览页保持一致。
- 显隐：跟底部控制条一致，点画面显示、3.5 秒后一起淡出。
- 竖屏时图标是「四角向外」，点了转横屏；横屏时变成「四角向内」，点了回竖屏。
- 横屏期间隐藏状态栏与导航栏（`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，从边缘划一下可临时唤出）。
- 退出播放页（返回键或关闭）时，方向还原成进入播放页之前的值。

## 实现

- 方向用 `Activity.requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE` 请求：
  用 `SENSOR_LANDSCAPE` 而不是 `LANDSCAPE`，用户把手机翻过来也不会出现倒着的画面；
  该请求不受系统「自动旋转」开关限制，关掉自动旋转也能转。
- 退出横屏回到 `restoreOrientation`：原来是 `UNSPECIFIED` / `SENSOR` 时直接回 `PORTRAIT`，
  否则人在横屏姿态下点退出会看着毫无反应。`DisposableEffect` 在播放页销毁时兜底还原，
  避免列表页停在横屏。
- `AndroidManifest.xml` 给 `MainActivity` 加
  `configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"`：
  不加的话转屏会重建 Activity，ExoPlayer 被 `release`，播放从头开始、还要重新复制一次文件。
  Compose 通过 `LocalConfiguration` 拿到新方向，`remember` 的状态与播放进度都保留。
  仓库里没有 `values-land` 之类的限定符资源，不需要靠重建去重新解析资源。
- 系统栏的显隐走 `WindowInsetsControllerCompat`，`DisposableEffect(landscape)` 进出各处理一次；
  隐藏后 `safeDrawing` inset 变 0，控制层自然贴边（横屏时挖孔仍在 `displayCutout` 里，控件会退开）。

## 验证

- `lintDebug` 无告警，四 ABI `assembleDebug` 通过，单测 151 条全绿。
- 真机：竖屏点按钮转横屏，画面居中留边、播放不中断；再点回竖屏；从横屏直接返回列表，
  列表是竖屏；横屏下上下控件都没被挖孔或手势区压到。
