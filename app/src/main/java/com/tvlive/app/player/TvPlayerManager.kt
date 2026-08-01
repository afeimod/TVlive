package com.tvlive.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 基于 Media3 ExoPlayer 的电视直播播放器管理
 *
 * 支持 HLS(m3u8)、HTTP/HTTPS 直播流
 * 自动重连、错误处理
 */
class TvPlayerManager(private val context: Context) {

    var player: ExoPlayer? = null
        private set

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var currentUrl: String? = null
    private var retryCount = 0
    private val maxRetry = 3

    var onError: ((String) -> Unit)? = null
    var onLoading: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null

    fun createPlayer(): ExoPlayer {
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(okHttpClient).setUserAgent("TVLive/1.0")
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> onLoading?.invoke()
                            Player.STATE_READY -> {
                                retryCount = 0
                                onReady?.invoke()
                            }
                            Player.STATE_ENDED -> { /* 直播不会结束 */ }
                            Player.STATE_IDLE -> { }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (retryCount < maxRetry) {
                            retryCount++
                            // 自动重连
                            postDelayed(1000L) {
                                currentUrl?.let { play(it) }
                            }
                        } else {
                            onError?.invoke("播放失败: ${error.errorCodeName}. 请尝试切换其他源或频道")
                        }
                    }
                })
                playWhenReady = true
            }
    }

    /**
     * 播放指定 URL
     */
    fun play(url: String) {
        currentUrl = url
        retryCount = 0

        val player = player ?: createPlayer().also { player = it }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.02f)
                    .build()
            )
            .build()

        val mediaSource = createMediaSource(url, mediaItem)
        player.setMediaSource(mediaSource)
        player.prepare()
    }

    private fun createMediaSource(url: String, mediaItem: MediaItem): MediaSource {
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(okHttpClient).setUserAgent("TVLive/1.0")
        )

        return if (url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }

    fun release() {
        player?.release()
        player = null
        currentUrl = null
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    private fun postDelayed(delayMs: Long, action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, delayMs)
    }
}
