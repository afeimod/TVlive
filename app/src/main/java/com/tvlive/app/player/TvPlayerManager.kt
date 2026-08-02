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
import com.tvlive.app.network.NetworkConfig
import okhttp3.OkHttpClient

/**
 * 基于 Media3 ExoPlayer 的电视直播播放器管理
 *
 * 支持 HLS(m3u8)、HTTP/HTTPS 直播流
 * 自动重连、错误处理
 *
 * 网络优化: 自定义DNS + TLS兼容 + IPv4优先 + 连接池
 */
class TvPlayerManager(private val context: Context) {

    var player: ExoPlayer? = null
        private set

    // 使用优化的OkHttpClient: 自定义DNS + TLS兼容 + IPv4优先
    private val okHttpClient: OkHttpClient = NetworkConfig.createPlayerClient(context).build()

    private var currentUrl: String? = null
    private var retryCount = 0
    private val maxRetry = 5  // 增加重试次数, 电视网络不稳定

    var onError: ((String) -> Unit)? = null
    var onLoading: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null

    fun createPlayer(): ExoPlayer {
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent(NetworkConfig.USER_AGENT)
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
                            // 自动重连, 间隔递增: 1s, 2s, 3s, 4s, 5s
                            val delay = (retryCount * 1000).toLong()
                            postDelayed(delay) {
                                currentUrl?.let { play(it) }
                            }
                        } else {
                            // 清除DNS缓存, 下次重新解析
                            NetworkConfig.clearDnsCache()
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
            OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent(NetworkConfig.USER_AGENT)
        )

        // 判断是否为HLS流: m3u8后缀, 或URL中包含m3u8
        val isHls = url.contains(".m3u8", ignoreCase = true) ||
                    url.contains("m3u8", ignoreCase = true) ||
                    url.contains("/hls/", ignoreCase = true)

        return if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)  // 无分块预准备, 加速启动
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
