package top.aico.wxcacheviewer.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Job
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
import kotlin.math.min

/** 放大上限按行业惯例取值：微信与各系统相册多在 3~5 倍，取 4 倍。 */
private const val MAX_IMAGE_SCALE = 4f

/** 最小就是 1 倍，也就是放大前的 Fit 大小，不允许缩得比原来看起来更小。 */
private const val MIN_IMAGE_SCALE = 1f

/** 捏到接近 1 倍时算「缩回原状」，抬手后吸附归位。 */
private const val SNAP_BACK_SCALE = 1.15f

/** 判定「当前是否处于放大状态」的阈值，用于锁住翻页。 */
private const val ZOOM_EPSILON = 1.01f


/** 能旋转的只有静态图片，动图（表情包）不参与旋转。 */
private val FileType.isRotatable: Boolean
    get() = this == FileType.JPEG || this == FileType.PNG || this == FileType.WEBP

/**
 * 顺时针推进 90 度，满 360 度回到 0：0 → 90 → 180 → 270 → 0。
 * 负角度（理论上不会出现）也收敛到 [0, 360) 区间。
 */
internal fun nextRotation(current: Int): Int = (current + 90).mod(360)

/** 缩放状态：倍率 + 相对容器中心的位移（屏幕像素）。 */
internal data class ZoomState(val scale: Float, val offset: Offset)

/** 位移必须是有限值，NaN / Infinity 一旦写进绘制层，图片就整个不画了。 */
private fun Offset.isFinite(): Boolean = x.isFinite() && y.isFinite()

/**
 * 算一次缩放/平移之后的状态。
 *
 * - 倍率夹在 [MIN_IMAGE_SCALE]..[MAX_IMAGE_SCALE]，所以永远不会比原来的 Fit 更小；
 * - 绕双指中心 [centroid] 缩放，焦点下那块画面保持不动；
 * - 位移夹在「内容不露出黑边」的范围内，内容比视口小时直接锁在中心。
 *
 * 手指抬起的那一帧取不到质心（Compose 返回 `Offset.Unspecified`，也就是 NaN），
 * 此时直接返回原来的状态：`NaN * 0` 依然是 NaN，一旦写进 offset，图片就整个不绘制了。
 */
internal fun zoomTransform(
    oldScale: Float,
    oldOffset: Offset,
    zoom: Float,
    pan: Offset,
    centroid: Offset,
    viewport: IntSize,
    contentWidth: Float,
    contentHeight: Float
): ZoomState {
    val target = (oldScale * zoom).coerceIn(MIN_IMAGE_SCALE, MAX_IMAGE_SCALE)
    if (target <= MIN_IMAGE_SCALE) return ZoomState(MIN_IMAGE_SCALE, Offset.Zero)
    if (!centroid.isFinite() || !pan.isFinite() || !zoom.isFinite()) {
        return ZoomState(target, if (oldOffset.isFinite()) oldOffset else Offset.Zero)
    }
    val ratio = target / oldScale
    val pivot = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
    val moved = oldOffset * ratio + pivot * (1f - ratio) + pan
    if (!moved.isFinite()) return ZoomState(oldScale, oldOffset)
    val maxX = ((contentWidth * target - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((contentHeight * target - viewport.height) / 2f).coerceAtLeast(0f)
    return ZoomState(
        scale = target,
        offset = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
    )
}

/**
 * 双指缩放 + 放大后的单指平移。
 *
 * 只有两种情况下才消费事件，其余一律放行，翻页仍归 [HorizontalPager]：
 * - 触点 ≥ 2：马上按双指缩放/平移处理；
 * - 已经放大且单指拖动超过 touchSlop：接管成平移（没过 slop 前不消费，
 *   点按切换工具条、点按钮都不会被抢走）。
 */
private suspend fun PointerInputScope.detectZoomAndPan(
    canPan: () -> Boolean,
    onTransform: (centroid: Offset, zoom: Float, pan: Offset) -> Unit,
    onGestureEnd: () -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var multiTouch = false
        var panStarted = false
        var accumulated = Offset.Zero
        do {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount >= 2) multiTouch = true
            // 抬手那一帧没有任何按下的手指，质心是 NaN，处理它只会把状态算坏
            if (pressedCount > 0) {
                when {
                    multiTouch -> {
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            onTransform(event.calculateCentroid(useCurrent = false), zoom, pan)
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }

                    canPan() -> {
                        val pan = event.calculatePan()
                        accumulated += pan
                        if (!panStarted && accumulated.getDistance() > viewConfiguration.touchSlop) {
                            panStarted = true
                        }
                        if (panStarted && pan != Offset.Zero) {
                            onTransform(event.calculateCentroid(useCurrent = false), 1f, pan)
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                }
            }
        } while (event.changes.any { it.pressed })
        onGestureEnd()
    }
}

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
    // 当前处于放大状态的图：放大时锁住翻页，手都留给图片拖动，缩回 1 倍再放开
    var zoomedKey by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = zoomedKey != current.key,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val file = files[page]
            ViewerPage(
                file = file,
                preparer = preparer,
                rotation = rotations[file.key] ?: 0,
                onTap = { if (file.type.isRotatable) chromeVisible = !chromeVisible },
                onZoomChanged = { key, zoomed ->
                    if (zoomed) {
                        zoomedKey = key
                    } else if (zoomedKey == key) {
                        zoomedKey = null
                    }
                }
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
    onTap: () -> Unit,
    onZoomChanged: (String, Boolean) -> Unit
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

    val rotatable = file.type.isRotatable
    val scope = rememberCoroutineScope()
    var scale by remember(file.key) { mutableFloatStateOf(MIN_IMAGE_SCALE) }
    var offset by remember(file.key) { mutableStateOf(Offset.Zero) }
    var settleJob by remember(file.key) { mutableStateOf<Job?>(null) }

    // 换图或旋转都会让原来的布局框失效，缩放和偏移一并归零
    LaunchedEffect(file.key, rotation) {
        settleJob?.cancel()
        scale = MIN_IMAGE_SCALE
        offset = Offset.Zero
    }

    // 放大时才锁住翻页；缩回 1 倍立刻放开，滑动切换照旧
    LaunchedEffect(file.key, rotatable) {
        if (!rotatable) return@LaunchedEffect
        snapshotFlow { scale > ZOOM_EPSILON }.collect { onZoomChanged(file.key, it) }
    }
    // 页面被回收时不能把「放大中」留在父层，否则翻页会一直锁着
    DisposableEffect(file.key) {
        onDispose { onZoomChanged(file.key, false) }
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
            image != null -> {
                val painter = rememberAsyncImagePainter(
                    model = image,
                    imageLoader = CoilImageLoaders.animated
                )
                val intrinsic = painter.intrinsicSize
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 56.dp)
                ) {
                    // 转 90/270 度时把布局框的宽高换过来再旋转，旋转后的画面正好铺满原来的可视区
                    val quarterTurn = rotation % 180 != 0
                    val boxWidth = if (quarterTurn) maxHeight else maxWidth
                    val boxHeight = if (quarterTurn) maxWidth else maxHeight

                    val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
                    val boxPxW = if (quarterTurn) viewport.height else viewport.width
                    val boxPxH = if (quarterTurn) viewport.width else viewport.height
                    // 图片按 Fit 落在布局框里的实际尺寸；旋转 90 度后宽高互换就是屏幕上的尺寸，
                    // 拖动边界要按这个算，否则会允许把画面拖出黑边
                    val fit = if (intrinsic.isSpecified && boxPxW > 0 && boxPxH > 0) {
                        val ratio = min(
                            boxPxW.toFloat() / intrinsic.width,
                            boxPxH.toFloat() / intrinsic.height
                        )
                        Size(intrinsic.width * ratio, intrinsic.height * ratio)
                    } else {
                        Size.Zero
                    }
                    val contentW = if (quarterTurn) fit.height else fit.width
                    val contentH = if (quarterTurn) fit.width else fit.height

                    // 缩放只做在静态图片上，表情包（动图）跟旋转一样保持原样
                    val zoomGesture = if (rotatable) {
                        Modifier.pointerInput(rotation, fit, viewport) {
                            detectZoomAndPan(
                                canPan = { scale > MIN_IMAGE_SCALE },
                                onTransform = { centroid, zoom, pan ->
                                    settleJob?.cancel()
                                    val next = zoomTransform(
                                        oldScale = scale,
                                        oldOffset = offset,
                                        zoom = zoom,
                                        pan = pan,
                                        centroid = centroid,
                                        viewport = viewport,
                                        contentWidth = contentW,
                                        contentHeight = contentH
                                    )
                                    scale = next.scale
                                    offset = next.offset
                                },
                                onGestureEnd = {
                                    // 捏到 1 倍边上就吸附归位，不然会卡在 1.0x 附近挡住翻页
                                    if (scale > MIN_IMAGE_SCALE && scale < SNAP_BACK_SCALE) {
                                        val fromScale = scale
                                        val fromOffset = offset
                                        settleJob = scope.launch {
                                            animate(
                                                initialValue = 0f,
                                                targetValue = 1f,
                                                animationSpec = tween(durationMillis = 140)
                                            ) { fraction, _ ->
                                                scale = fromScale +
                                                    (MIN_IMAGE_SCALE - fromScale) * fraction
                                                offset = fromOffset * (1f - fraction)
                                            }
                                            scale = MIN_IMAGE_SCALE
                                            offset = Offset.Zero
                                        }
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // 放大后的超出部分裁在可视区内，别糊到顶栏和状态栏上
                            .clipToBounds()
                            .then(zoomGesture)
                    ) {
                        Image(
                            painter = painter,
                            contentDescription = file.name,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .requiredSize(width = boxWidth, height = boxHeight)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                                .graphicsLayer { rotationZ = rotation.toFloat() },
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            prepareFailed -> Text(
                text = stringResource(id = R.string.file_open_failed, file.name),
                color = Color.White
            )

            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}
