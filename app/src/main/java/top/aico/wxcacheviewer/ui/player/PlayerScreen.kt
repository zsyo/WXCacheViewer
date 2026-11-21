package top.aico.wxcacheviewer.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.playback.VideoPreparer
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.ui.common.formatDuration

@Composable
fun PlayerScreen(file: CacheFile, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preparer = remember { VideoPreparer(RootCommandExecutor()) }
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    val videoPlayer = remember(file.name) { SimpleVideoPlayer(context) }

    var copying by remember(file.name) { mutableStateOf(true) }
    var ready by remember(file.name) { mutableStateOf(false) }
    var error by remember(file.name) { mutableStateOf<String?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var volumePanelVisible by remember { mutableStateOf(false) }
    var seekHint by remember { mutableIntStateOf(0) }
    var suppressTap by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    DisposableEffect(videoPlayer) {
        onDispose { videoPlayer.release() }
    }

    val keepControlsVisible: () -> Unit = {
        controlsVisible = true
        interactionTick++
    }

    LaunchedEffect(file.name) {
        copying = true
        ready = false
        error = null
        try {
            val prepared = preparer.prepare(context, file)
            videoPlayer.setSource(prepared.absolutePath)
        } catch (e: Exception) {
            error = e.message ?: context.getString(R.string.player_prepare_failed)
        }
        copying = false
    }

    videoPlayer.onPrepared = {
        durationMs = videoPlayer.durationMs().toLong()
        ready = true
        controlsVisible = true
    }
    videoPlayer.onPlayingChanged = { playing -> isPlaying = playing }
    videoPlayer.onCompletion = {
        isPlaying = false
        positionMs = durationMs
        keepControlsVisible()
    }
    videoPlayer.onError = { message ->
        error = context.getString(R.string.player_play_failed_generic, message)
        isPlaying = false
    }

    LaunchedEffect(controlsVisible, isPlaying, interactionTick) {
        if (controlsVisible && isPlaying) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(isPlaying, ready) {
        while (isPlaying) {
            if (!dragging) {
                positionMs = videoPlayer.currentPositionMs().toLong()
            }
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(ready) {
        if (ready && durationMs <= 0L) {
            durationMs = videoPlayer.durationMs().toLong()
        }
    }

    val togglePlayback: () -> Unit = {
        videoPlayer.toggle()
        isPlaying = videoPlayer.isPlaying
        keepControlsVisible()
    }

    val seekBy: (Long) -> Unit = { delta ->
        if (durationMs > 0L) {
            val target = (videoPlayer.currentPositionMs() + delta).coerceIn(0L, durationMs)
            videoPlayer.seekTo(target.toInt())
            positionMs = target
        }
    }

    val applySpeed: (Float) -> Unit = { next ->
        videoPlayer.setSpeed(next)
        speed = next
        keepControlsVisible()
    }

    val cycleSpeed: () -> Unit = {
        val index = SPEED_STEPS.indexOf(speed)
        applySpeed(SPEED_STEPS[(index + 1) % SPEED_STEPS.size])
    }

    val changeVolume: (Float) -> Unit = { value ->
        val target = value.toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    val preparing = copying || (!ready && error == null)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    player = videoPlayer.player
                }
            }
        )

        // 手势层：单击切换控制层显隐，长按左右两侧连续快退 / 快进
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (suppressTap) {
                                suppressTap = false
                            } else {
                                controlsVisible = !controlsVisible
                                interactionTick++
                            }
                        },
                        onPress = { offset ->
                            suppressTap = false
                            val forward = offset.x >= size.width / 2f
                            val job = scope.launch {
                                delay(LONG_PRESS_DELAY_MS)
                                suppressTap = true
                                seekHint = if (forward) 1 else -1
                                while (isActive) {
                                    seekBy(if (forward) SEEK_STEP_MS else -SEEK_STEP_MS)
                                    delay(SEEK_REPEAT_MS)
                                }
                            }
                            tryAwaitRelease()
                            job.cancel()
                            seekHint = 0
                        }
                    )
                }
        )

        if (preparing) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    text = stringResource(id = R.string.player_preparing),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        val errorMessage = error
        if (errorMessage != null && !copying) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = errorMessage,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(onClick = onClose) {
                    Text(text = stringResource(id = R.string.player_back))
                }
            }
        }

        if (seekHint != 0) {
            Text(
                text = stringResource(
                    id = if (seekHint > 0) {
                        R.string.player_seek_forward
                    } else {
                        R.string.player_seek_backward
                    }
                ),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-72).dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                        )
                    )
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
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
                    text = file.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (controlsVisible && !preparing && errorMessage == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = togglePlayback
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = stringResource(
                        id = if (isPlaying) R.string.player_pause else R.string.player_play
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                AnimatedVisibility(visible = volumePanelVisible) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volume),
                            contentDescription = stringResource(id = R.string.player_volume),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Slider(
                            value = volume.toFloat().coerceIn(0f, maxVolume.toFloat()),
                            onValueChange = changeVolume,
                            valueRange = 0f..maxVolume.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatDuration(positionMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = positionMs.toFloat()
                            .coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                        onValueChange = {
                            dragging = true
                            positionMs = it.toLong()
                            interactionTick++
                        },
                        onValueChangeFinished = {
                            videoPlayer.seekTo(positionMs.toInt())
                            dragging = false
                            keepControlsVisible()
                        },
                        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Text(
                        text = formatDuration(durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = cycleSpeed) {
                        Text(
                            text = formatSpeed(speed),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Box(modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        volumePanelVisible = !volumePanelVisible
                        interactionTick++
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volume),
                            contentDescription = stringResource(id = R.string.player_volume),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

private fun formatSpeed(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString()
    } else {
        speed.toString()
    }
    return "${text}x"
}

private const val CONTROLS_HIDE_DELAY_MS = 3_500L
private const val POSITION_POLL_INTERVAL_MS = 200L
private const val LONG_PRESS_DELAY_MS = 350L
private const val SEEK_STEP_MS = 3_000L
private const val SEEK_REPEAT_MS = 220L
private val SPEED_STEPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
