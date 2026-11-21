# 播放结束后点击播放按钮从头重播

## 问题

视频播放到结尾时，ExoPlayer 进入 `STATE_ENDED`。该状态下调用 `Player.play()` 不会产生任何效果——必须先把播放位置 seek 回起点，否则会一直停在结尾。因此播放结束后中央播放按钮点击无反应。

## 实现

- `SimpleVideoPlayer` 新增 `isEnded`（`playbackState == Player.STATE_ENDED`）。
- `SimpleVideoPlayer.toggle()`：仅在 `STATE_ENDED` 时先 `seekTo(0)` 再 `play()`；播放中暂停、暂停中继续这两条分支保持原样，不受影响。
- `PlayerScreen.togglePlayback()`：在调用 `toggle()` 前判断 `isEnded`，是则把 `positionMs` 置 0，让进度条与时间文字立刻回到起点，而不是等下一次轮询才更新。

## 验证

- `assembleDebug`、`testDebugUnitTest`、`lintDebug` 全部通过。
- APK 经 MT 安装到设备。
- 需真机确认：播放到结尾后点中央播放按钮应从头开始；播放中暂停再继续仍是断点续播；暂停时拖动进度条后继续播放也正常。
