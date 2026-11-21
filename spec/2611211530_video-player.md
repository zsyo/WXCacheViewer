# 视频播放页

## 目标
在相册列表中点击任意视频缓存，直接进入播放页观看，提供基础控制：播放/暂停、音量、进度拖动、长按左右半屏快退/快进、倍速。

## 方案

### 播放内核
- 使用 Media3 `ExoPlayer`（`androidx.media3:media3-exoplayer` + `media3-ui`，版本 1.5.1）。只引这两个模块，不含软解扩展，视频解码走系统 MediaCodec 硬解。
- 不使用 `VideoView`：其公开 API 没有倍速，`setSpeed` 经 javap 确认在 `android.widget.VideoView` 中不存在；宽高比也要自行计算。ExoPlayer 的 `setPlaybackSpeed` 稳定，`PlayerView` 默认 `RESIZE_MODE_FIT` 自动保持比例。
- `SimpleVideoPlayer` 封装 ExoPlayer：`setSource` 走 `Uri.fromFile`，`Player.Listener` 回调 `onPlaybackStateChanged`（READY/ENDED）、`onIsPlayingChanged`、`onPlayerError`。

### 视频准备
- `VideoPreparer`：用 root 通道把 `finder/video` 下的无扩展名缓存复制到 `cacheDir/playback/<name>.mp4`，`chmod 0644` 由 `WxPaths.copyFileCommand` 完成；已存在且大小一致则直接复用；目录内只保留最近 3 个，控制占用。
- 复制超时 180s（`RootCommandExecutor` 默认 10s 不够，27MB 文件需要更长）。

### 交互与 UI
- 列表卡片加 `clickable`，点击设置 `playingFile`；`GalleryScreen` 在 `Scaffold` 之后叠加全屏 `PlayerScreen`，返回后列表状态与滚动位置保持不变。
- 顶部渐变栏：返回按钮 + 文件名；中央圆形播放/暂停按钮；底部控制条：当前时间 / 进度 `Slider` / 总时长，下一行是倍速按钮与音量按钮。
- 单击视频区域切换控制层显隐，播放中 3.5s 无操作自动隐藏（`AnimatedVisibility` 淡入淡出）。
- 长按左右半屏：`detectTapGestures.onPress` 起协程，350ms 后每 220ms 跳 3 秒，松开停止；期间显示"快进/快退"提示，并用 `suppressTap` 抑制长按结束时的误触 tap。
- 音量走系统媒体音量（`AudioManager.STREAM_MUSIC`），点击音量图标展开横向滑块。
- 倍速循环 0.5 / 0.75 / 1 / 1.25 / 1.5 / 2，按钮显示当前值（如 `1.5x`）。
- `BackHandler` 与返回按钮都关闭播放页，`onDispose` 释放 ExoPlayer。

### 体积控制
- `release` 开启 `isMinifyEnabled` 与 `isShrinkResources`：debug 包 12.9MB（未压缩），release unsigned 包仅 2.1MB。

## 完成标准
`assembleDebug`、`testDebugUnitTest`、`lintDebug` 通过（新增 `formatDuration` 的 3 个用例）；真机点击列表项进入播放页，播放/暂停、进度拖动、音量、倍速、长按快进快退均生效，返回后列表位置不变。
