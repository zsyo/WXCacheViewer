package top.aico.wxcacheviewer.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.image.CoilImageLoaders
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.media.MediaPreparer
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.ui.common.formatFileSize
import top.aico.wxcacheviewer.ui.common.formatModifiedAt
import java.io.File

/** 能旋转的只有静态图片，动图（表情包）不参与旋转。 */
private val FileType.isRotatable: Boolean
    get() = this == FileType.JPEG || this == FileType.PNG || this == FileType.WEBP

/**
 * 顺时针推进 90 度，满 360 度回到 0：0 → 90 → 180 → 270 → 0。
 * 负角度（理论上不会出现）也收敛到 [0, 360) 区间。
 */
internal fun nextRotation(current: Int): Int = (current + 90).mod(360)

/**
 * 全屏图片/动图预览：左右滑动翻页，顶部可保存到相册。
 * 图片本身在微信私有目录里，先由 [MediaPreparer] 复制到应用缓存目录再交给 Coil。
 *
 * 顶部的文件名可以长按复制——排查具体某张图时（比如反馈速度不对），
 * 手抄一串 md5 不现实，复制出来直接贴给开发者最快。
 *
 * 静态图片还支持旋转：右侧悬浮按钮每按一次顺时针 90 度，角度按文件名各自记录，
 * 翻到别的图不受影响。旋转只作用在图片的绘制层，顶栏与底栏不会跟着动。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
    files: List<CacheFile>,
    initialKey: String,
    preparer: MediaPreparer,
    onRequestSave: (CacheFile, (Boolean, String) -> Unit) -> Unit,
    onClose: () -> Unit
) {
    if (files.isEmpty()) return
    BackHandler(onBack = onClose)

    val startIndex = files.indexOfFirst { it.key == initialKey }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { files.size }
    val current = files[pagerState.currentPage.coerceIn(files.indices)]
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedHint = LocalResources.current.getString(R.string.viewer_name_copied)

    // 顶栏、底栏、旋转按钮一起显隐，点图片或黑边切换；动图页保持常显，避免表情包来回闪
    var chromeVisible by remember { mutableStateOf(true) }
    val rotatable = current.type.isRotatable
    val chromeShown = chromeVisible || !rotatable
    // 旋转角度按文件名记录，滑走再滑回来还是原来的角度，且不会串到别的图
    val rotations = remember { mutableStateMapOf<String, Int>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val file = files[page]
            ViewerPage(
                file = file,
                preparer = preparer,
                rotation = rotations[file.key] ?: 0,
                onTap = { if (file.type.isRotatable) chromeVisible = !chromeVisible }
            )
        }

        AnimatedVisibility(
            visible = chromeShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_back),
                        contentDescription = stringResource(id = R.string.player_back),
                        tint = Color.White
                    )
                }
                Text(
                    text = current.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(current.name, current.name)
                                )
                                scope.launch { snackbarHostState.showSnackbar(copiedHint) }
                            }
                        )
                )
                IconButton(
                    onClick = {
                        onRequestSave(current) { _, message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_export),
                        contentDescription = stringResource(id = R.string.file_action_export),
                        tint = Color.White
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val modifiedLabel = formatModifiedAt(current.modifiedAt)
                val info = buildString {
                    append(current.type.name)
                    append(" / ")
                    append(formatFileSize(current.size))
                    if (modifiedLabel.isNotEmpty()) {
                        append(" / ")
                        append(modifiedLabel)
                    }
                    if (files.size > 1) {
                        append("  ·  ")
                        append(pagerState.currentPage + 1)
                        append("/")
                        append(files.size)
                    }
                }
                Text(
                    text = info,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // 旋转按钮单独浮在右侧，跟控制层一起显隐，顶栏只留「返回 + 标题 + 导出」
        AnimatedVisibility(
            visible = chromeShown && rotatable,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            IconButton(
                onClick = {
                    rotations[current.key] = nextRotation(rotations[current.key] ?: 0)
                },
                modifier = Modifier
                    // 横屏时挖孔可能在侧边，让它也退开
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.End)
                    )
                    .padding(end = 12.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_rotate_right),
                    contentDescription = stringResource(id = R.string.viewer_rotate),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp)
        )
    }
}

@Composable
private fun ViewerPage(
    file: CacheFile,
    preparer: MediaPreparer,
    rotation: Int,
    onTap: () -> Unit
) {
    var prepareFailed by remember(file.key) { mutableStateOf(false) }
    val prepared by produceState<File?>(initialValue = null, key1 = file.key) {
        // producer 异常会抛到组合导致整屏崩溃，兜住后显示失败提示而不是无限转圈
        value = try {
            // wxgf 是动图，要的是整段重编码的 GIF；其余按普通图片/动图处理
            if (file.type == FileType.WXGF) {
                preparer.prepareAnimated(file)
            } else {
                preparer.prepare(file)
            }
        } catch (e: Exception) {
            AppLog.log("viewer", "prepare failed ${file.key}: ${e.message}")
            null
        }
        // prepare 解不出来时返回 null 而不是抛异常（比如 wxgf 解码失败、密钥缺失），
        // 这时同样要落到失败提示，否则界面会一直转圈
        prepareFailed = value == null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 点图片本身或黑边都能切换控制层；拖动翻页时 detectTapGestures 会自动取消
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center
    ) {
        val image = prepared
        when {
            image != null -> BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 56.dp)
            ) {
                // 转 90/270 度时把布局框的宽高换过来再旋转，旋转后的画面正好铺满原来的可视区
                val quarterTurn = rotation % 180 != 0
                AsyncImage(
                    model = image,
                    imageLoader = CoilImageLoaders.animated,
                    contentDescription = file.name,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(
                            width = if (quarterTurn) maxHeight else maxWidth,
                            height = if (quarterTurn) maxWidth else maxHeight
                        )
                        .graphicsLayer { rotationZ = rotation.toFloat() },
                    contentScale = ContentScale.Fit
                )
            }

            prepareFailed -> Text(
                text = stringResource(id = R.string.file_open_failed, file.name),
                color = Color.White
            )

            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}
