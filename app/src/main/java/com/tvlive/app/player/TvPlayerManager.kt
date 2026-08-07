package com.tvlive.app.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import android.util.Log
import com.tvlive.app.net.HttpClientProvider
import com.tvlive.app.net.UrlHelper

/**
 * 基于 Media3 ExoPlayer 的电视直播播放器管理
 *
 * 支持 HLS(m3u8)、HTTP/HTTPS 直播流
 * 自动重连、错误处理
 *
 * **中国移动网络优化（关键）**：
 * - 一个频道可能对应多个 URL（主 URL + backupUrls + 自动生成的镜像 URL）
 * - 播放失败时自动按顺序尝试下一个 URL，直到成功或全部失败
 * - 每个 URL 单独计数重试，单 URL 失败 2 次后切换到下一个 URL
 * - 已失败的 URL 在本次播放会话内不再尝试（避免循环）
 */
class TvPlayerManager(private val context: Context) {

    var player: ExoPlayer? = null
        private set

    private val okHttpClient = HttpClientProvider.playerClient
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 当前频道的主 URL */
    private var currentUrl: String? = null

    /** 当前频道的所有备选 URL 列表（按优先级排序） */
    private var currentAlternativeUrls: List<String> = emptyList()

    /** 当前正在尝试的 URL 索引 */
    private var currentUrlIndex = 0

    /** 当前 URL 的重试次数 */
    private var retryCount = 0

    /** 单个 URL 最大重试次数（超过则切换到下一个 URL） */
    private val maxRetryPerUrl = 2

    /** 当前会话内已知失败的 URL（避免重复尝试） */
    private val failedUrls = mutableSetOf<String>()

    var onError: ((String) -> Unit)? = null
    var onLoading: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null
    /** 当前实际播放的 URL 变化时回调（用于 UI 显示） */
    var onUrlSwitched: ((url: String) -> Unit)? = null

    /**
     * Media3 AudioAttributes - 告诉系统这是媒体播放
     * 确保：
     * - 音量键调节媒体音量（而非铃声）
     * - 音频通过 HDMI 路由到电视扬声器/音响
     * - 与其他音频应用正确协调（如电话来电时降低音量）
     */
    private val mediaAudioAttributes = Media3AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    fun createPlayer(): ExoPlayer {
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(okHttpClient).setUserAgent(HttpClientProvider.USER_AGENT)
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // 5G 网络优化：使用 LoadControl 加快直播起播速度
        // - minBufferMs 较小：快速开始播放（500ms 缓冲即可起播）
        // - maxBufferMs 适中：避免缓冲过多数据占用内存
        // - bufferForPlaybackMs 较小：起播门槛低，5G 网络下可快速起播
        // - backBufferMs 较小：直播不需要回看，减少内存占用
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 500,
                /* maxBufferMs= */ 15000,
                /* bufferForPlaybackMs= */ 200,
                /* bufferForPlaybackAfterRebufferMs= */ 500
            )
            .setBackBuffer(/* backBufferDurationMs= */ 1000, /* retainBackBufferFromKeyframe= */ false)
            .setTargetBufferBytes(androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(mediaAudioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> onLoading?.invoke()
                            Player.STATE_READY -> {
                                // 重置当前 URL 的重试计数（播放成功）
                                retryCount = 0
                                onReady?.invoke()
                            }
                            Player.STATE_ENDED -> { /* 直播不会结束 */ }
                            Player.STATE_IDLE -> { }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handlePlayerError(error)
                    }
                })
                playWhenReady = true
            }
    }

    /**
     * 处理播放错误：当前 URL 重试或切换到下一个备选 URL
     */
    private fun handlePlayerError(error: PlaybackException) {
        val currentAttemptUrl = currentAlternativeUrls.getOrNull(currentUrlIndex) ?: currentUrl
        Log.w(TAG, "Player error on URL[$currentUrlIndex]: $currentAttemptUrl - ${error.errorCodeName}")

        // 标记当前 URL 为失败
        currentAttemptUrl?.let { failedUrls.add(it) }

        retryCount++
        if (retryCount <= maxRetryPerUrl && currentAttemptUrl != null) {
            // 同一 URL 重试（网络抖动等瞬时错误）
            Log.d(TAG, "Retrying URL[$currentUrlIndex] attempt $retryCount/$maxRetryPerUrl")
            postDelayed(800L) {
                currentAttemptUrl.let { playSingleUrl(it) }
            }
            return
        }

        // 当前 URL 已达重试上限，尝试切换到下一个备选 URL
        retryCount = 0
        val nextIndex = findNextAvailableUrlIndex()
        if (nextIndex >= 0) {
            currentUrlIndex = nextIndex
            val nextUrl = currentAlternativeUrls[nextIndex]
            Log.i(TAG, "Switching to alternative URL[$nextIndex]: $nextUrl")
            onUrlSwitched?.invoke(nextUrl)
            postDelayed(300L) {
                playSingleUrl(nextUrl)
            }
        } else {
            // 所有 URL 都失败
            Log.e(TAG, "All ${currentAlternativeUrls.size} URLs failed for channel")
            onError?.invoke(
                "播放失败：已尝试 ${currentAlternativeUrls.size} 个备用地址均无法连接。\n" +
                "可能原因：当前网络（如中国移动）对该直播源存在屏蔽，或所有源地址均已失效。\n" +
                "建议：在设置中刷新直播源，或切换到其他频道。"
            )
        }
    }

    /** 查找下一个未失败的备选 URL 索引 */
    private fun findNextAvailableUrlIndex(): Int {
        for (i in currentAlternativeUrls.indices) {
            if (i == currentUrlIndex) continue
            val url = currentAlternativeUrls[i]
            if (url !in failedUrls) return i
        }
        return -1
    }

    /**
     * 播放指定频道（支持多 URL 自动降级）
     *
     * @param url 频道主 URL
     * @param backupUrls M3U 解析出的备用 URL（可选）
     */
    fun play(url: String, backupUrls: List<String> = emptyList()) {
        currentUrl = url
        retryCount = 0
        failedUrls.clear()

        // 生成完整备选 URL 列表：镜像 URL + 备用 URL + 原始 URL
        currentAlternativeUrls = UrlHelper.getStreamAlternativeUrls(url, backupUrls)
        currentUrlIndex = 0

        Log.i(TAG, "Starting playback with ${currentAlternativeUrls.size} candidate URLs")
        currentAlternativeUrls.forEachIndexed { i, u ->
            Log.d(TAG, "  [$i] $u")
        }

        val player = player ?: createPlayer().also { player = it }
        playSingleUrl(currentAlternativeUrls.first())
    }

    /** 仅播放单个 URL（内部使用） */
    private fun playSingleUrl(url: String) {
        Log.d(TAG, "playSingleUrl: $url")
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
            OkHttpDataSource.Factory(okHttpClient).setUserAgent(HttpClientProvider.USER_AGENT)
        )

        return if (url.contains(".m3u8", ignoreCase = true)) {
            // 5G 网络优化：HLS 直播使用较短的 playlist 重载间隔和更快的错误检测
            // - minLoadableRetryCount：5G 网络下重试 3 拿足够，避免长时间卡在失败 URL
            // - playlist loader 通过 OkHttp 的超时控制
            HlsMediaSource.Factory(dataSourceFactory)
                .setMinLoadableRetryCount(3)
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
                .setMinLoadableRetryCount(3)
                .createMediaSource(mediaItem)
        }
    }

    fun release() {
        player?.release()
        player = null
        currentUrl = null
        currentAlternativeUrls = emptyList()
        currentUrlIndex = 0
        retryCount = 0
        failedUrls.clear()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    // ==================== 音量控制（电视遥控器音量键） ====================

    /**
     * 增大音量（遥控器音量+键）
     * 直接调节系统媒体音量流，确保电视扬声器/音响输出
     */
    fun volumeUp() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    /**
     * 减小音量（遥控器音量-键）
     */
    fun volumeDown() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    /**
     * 静音/取消静音切换
     */
    fun toggleMute(): Boolean {
        if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_UNMUTE,
                AudioManager.FLAG_SHOW_UI
            )
            return false
        } else {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                AudioManager.FLAG_SHOW_UI
            )
            return true
        }
    }

    /** 获取当前音量 (0-100) */
    fun getVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (current * 100 / max) else 0
    }

    private fun postDelayed(delayMs: Long, action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, delayMs)
    }

    companion object {
        private const val TAG = "TvPlayerManager"
    }
}
