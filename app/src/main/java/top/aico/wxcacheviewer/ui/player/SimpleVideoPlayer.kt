package top.aico.wxcacheviewer.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * ExoPlayer 的轻量封装，供播放页直接使用。
 * 选 Media3 而不是 VideoView：VideoView 没有公开倍速接口，且宽高比要自己算。
 */
class SimpleVideoPlayer(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    var onPrepared: (() -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> onPrepared?.invoke()
                Player.STATE_ENDED -> onCompletion?.invoke()
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onPlayingChanged?.invoke(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            onError?.invoke("${error.errorCodeName} ${error.message.orEmpty()}".trim())
        }
    }

    init {
        player.addListener(listener)
    }

    fun setSource(path: String) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        player.prepare()
        player.playWhenReady = true
    }

    val isPlaying: Boolean
        get() = player.isPlaying

    fun durationMs(): Int =
        if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt()

    fun currentPositionMs(): Int = player.currentPosition.toInt()

    fun toggle() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Int) {
        player.seekTo(positionMs.toLong())
    }

    fun setSpeed(value: Float) {
        player.setPlaybackSpeed(value)
    }

    /** 应用内音量，0f..1f。只作用于播放器自身，不影响系统媒体音量。 */
    fun setVolume(value: Float) {
        player.volume = value.coerceIn(0f, 1f)
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}
