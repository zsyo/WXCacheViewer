package top.aico.wxcacheviewer.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import top.aico.wxcacheviewer.ui.common.formatFileSize
import top.aico.wxcacheviewer.ui.common.formatModifiedAt
import java.io.File

/**
 * 全屏图片/动图预览：左右滑动翻页，顶部可保存到相册。
 * 图片本身在微信私有目录里，先由 [MediaPreparer] 复制到应用缓存目录再交给 Coil。
 */
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ViewerPage(file = files[page], preparer = preparer)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
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
                modifier = Modifier.weight(1f)
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
private fun ViewerPage(file: CacheFile, preparer: MediaPreparer) {
    var prepareFailed by remember(file.key) { mutableStateOf(false) }
    val prepared by produceState<File?>(initialValue = null, key1 = file.key) {
        // producer 异常会抛到组合导致整屏崩溃，兜住后显示失败提示而不是无限转圈
        value = try {
            preparer.prepare(file)
        } catch (e: Exception) {
            AppLog.log("viewer", "prepare failed ${file.key}: ${e.message}")
            null
        }
        // prepare 解不出来时返回 null 而不是抛异常（比如 wxgf 解码失败、密钥缺失），
        // 这时同样要落到失败提示，否则界面会一直转圈
        prepareFailed = value == null
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = prepared
        when {
            image != null -> AsyncImage(
                model = image,
                imageLoader = CoilImageLoaders.animated,
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 56.dp),
                contentScale = ContentScale.Fit
            )

            prepareFailed -> Text(
                text = stringResource(id = R.string.file_open_failed, file.name),
                color = Color.White
            )

            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}
