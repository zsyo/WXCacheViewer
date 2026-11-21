# 垂直音量面板喇叭图标改为静音切换

## 背景

垂直音量面板底部原为一个纯展示的喇叭图标，无交互。用户需要静音开关，且希望是「静音 / 恢复上次音量」的双向切换，而不是每次重新拖回某个位置。

## 实现

- 新增状态 `lastAudibleVolume`：任何一次非零音量（拖动、点按或初次读取持久化值）都会更新它，作为取消静音时的恢复目标。
- 新增 `toggleMute()`：
  - 当前音量大于阈值（`VOLUME_EPSILON = 0.001f`）→ 记下当前音量并置 0。
  - 当前为静音 → 恢复到 `lastAudibleVolume`（下限 0.05f，避免历史值异常时恢复后仍然听不到）。
  - 切换后立即写入 `SettingsRepository.playbackVolume`，杀掉应用再进来仍保持该状态。
- 面板底部图标改为 `IconButton`：
  - 静音时显示新增的 `ic_volume_off`（喇叭带叉）并降低不透明度；
  - 有声时显示 `ic_volume`；
  - `contentDescription` 分别为「恢复音量」/「静音」，新增字符串 `player_unmute`、`player_mute`。
- 控制条上的音量按钮同步按静音状态切换图标，静音时也能一眼看出。

## 验证

- `assembleDebug`、`testDebugUnitTest`、`lintDebug` 全部通过；release 包仍为 2.1MB。
- APK 经 MT 安装到设备。
- 需真机确认：拖到 43% 左右后点喇叭 → 静音且百分比变 0%；再点一次 → 回到 43% 左右；静音时拖动滑块可正常出声并更新恢复目标。
