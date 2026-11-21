package top.aico.wxcacheviewer.data.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * 语音播放器：只处理音频，不需要画面，因此比 [top.aico.wxcacheviewer.ui.player.PlayerScreen]
 * 轻得多，直接在列表里内联播放。
 *
 * 音频属性按「语音」声明，拔出耳机时自动暂停，避免突然外放。
 */
class VoicePlayer(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    /** 播放到结尾。 */
    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onCompletion?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke("${error.errorCodeName} ${error.message.orEmpty()}".trim())
            }
        })
    }

    fun setSource(path: String) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        player.prepare()
        player.play()
    }

    fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    val isPlaying: Boolean
        get() = player.isPlaying

    fun positionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    /** 时长未就绪时返回 0，界面据此只显示不确定态进度。 */
    fun durationMs(): Long = if (player.duration == C.TIME_UNSET) 0 else player.duration

    fun release() {
        player.release()
    }
}
