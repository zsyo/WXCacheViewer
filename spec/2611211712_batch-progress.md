# 批量导出/删除显示进度

## 需求

一次性导出或删除的视频较多时，操作需要较长时间，界面此前只有按钮变灰，用户无法判断是否在推进。需要在操作期间显示进度。

## 实现

### 状态

- 新增 `BatchAction`（EXPORT / DELETE）与 `BatchProgress(action, done, total)`。
- `GalleryUiState.actionBusy` 由普通字段改为派生属性 `batchProgress != null`，避免「忙碌」与「进度」两个字段互相打架。
- 每完成一项调用 `publishProgress` 刷新 `done`；若进度已被清理（action 不匹配）则忽略，防止界面重建后写入脏状态。

### 批量操作

- `exportSelected` / `deleteSelected` 在启动时写入 `BatchProgress(action, 0, total)`，循环体内每处理完一个文件（无论成功或失败）`done++` 并刷新，收尾时置 `null` 并给出原有汇总 Snackbar。

### 界面

- 工具条在 `batchProgress != null` 时原地把五个图标按钮替换为一行：`CircularProgressIndicator`（determinate，2.5dp 描边、22dp）+「正在导出 12/100」/「正在删除 12/100」。
- 文本与内边距让该行高度与按钮行接近，避免工具条上下跳动；期间不提供操作按钮，防止重复触发。

## 新增资源

- 字符串 `batch_export_progress`、`batch_delete_progress`。

## 验证

- `assembleDebug`、`testDebugUnitTest`、`lintDebug` 全部通过。
- APK 经 MT 安装到设备。
- 需真机确认：全选后导出时工具条出现环形进度且计数递增，完成后恢复五个按钮并弹出导出结果；批量删除同理；单项操作时进度直接 0→1 后结束。
