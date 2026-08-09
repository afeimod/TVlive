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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import android.util.Log
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.ResolvedUrl
import com.tvlive.app.net.HttpClientProvider
import com.tvlive.app.net.ISPDetector
import com.tvlive.app.net.UrlHelper

/**
 * 基于 Media3 ExoPlayer 的电视直播播放器管理（v3 - 按APK运行时逻辑）
 *
 * 核心逻辑（与APK的IjkVideoView完全一致）：
 *
 * 1. **播放器分流**（APK的setVideoPath逻辑）：
 *    - sys_ 前缀 → 系统播放器（ExoPlayer with DefaultHttpDataSource）
 *      系统播放器走系统DNS解析，不受IJK DNS缓存影响
 *      配合dns_cache_clear=1（APK中每次播放都清DNS缓存）
 *    - 其他 → 默认播放器（ExoPlayer with OkHttpDataSource + 桌面UA + 自定义DNS）
 *
 * 2. **协议解析**（APK的setVideoPath逻辑）：
 *    - sys_http://xxx → http://xxx + 系统播放器
 *    - ikk://?url=xxx → 提取xxx
 *    - ikkHeaders://?url=xxx&Referer=yyy → 提取xxx + 注入Headers
 *    - http(s):// → 直接播放
 *
 * 3. **URL索引切换**（APK的Channel.urlIndex逻辑）：
 *    - 每个频道有多个URL（urls列表）
 *    - 播放失败 → urlIndex+1，尝试下一个URL
 *    - 单URL重试2次后切换
 *    - 所有URL失败 → 报错
 *
 * 4. **DNS防污染**（APK的dns_cache_clear逻辑）：
 *    - 每次播放新URL时通过SafeDns清理缓存
 *    - 系统播放器自动走系统DNS（不受IJK缓存影响）
 */
class TvPlayerManager(private val context: Context) {

    var player: ExoPlayer? = null
        private set

    private val okHttpClient = HttpClientProvider.playerClient
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 当前频道的主URL */
    private var currentUrl: String? = null

    /** 当前频道的所有备选URL列表（按优先级排序，保留协议前缀） */
    private var currentAlternativeUrls: List<String> = emptyList()

    /** 当前正在尝试的URL索引（对应APK的Channel.urlIndex） */
    private var currentUrlIndex = 0

    /** 当前URL的重试次数 */
    private var retryCount = 0

    /** 单个URL最大重试次数（超过则切换到下一个URL，对应APK的reconnect=3） */
    private val maxRetryPerUrl = 2

    /** 当前会话内已知失败的URL（避免重复尝试） */
    private val failedUrls = mutableSetOf<String>()

    /** 当前播放URL对应的自定义Headers（ikkHeaders://协议注入） */
    private var currentHeaders: Map<String, String> = emptyMap()

    /** 当前是否使用系统播放器（sys_前缀 → true） */
    private var isSysPlayer = false

    var onError: ((String) -> Unit)? = null
    var onLoading: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null
    /** 当前实际播放的URL变化时回调（用于UI显示） */
    var onUrlSwitched: ((url: String) -> Unit)? = null

    private val mediaAudioAttributes = Media3AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    fun createPlayer(): ExoPlayer {
        val dataSourceFactory = createDataSourceFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // 5G/移动网络优化：加快直播起播速度
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 15000, 200, 500)
            .setBackBuffer(1000, false)
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
     * 处理播放错误：当前URL重试或切换到下一个备选URL
     * 对应APK的onError → LiveActivity.f.b(1000L) → urlIndex+1
     */
    private fun handlePlayerError(error: PlaybackException) {
        val currentAttemptUrl = currentAlternativeUrls.getOrNull(currentUrlIndex) ?: currentUrl
        Log.w(TAG, "Player error on URL[$currentUrlIndex]: $currentAttemptUrl - ${error.errorCodeName}")

        currentAttemptUrl?.let { failedUrls.add(it) }

        retryCount++
        if (retryCount <= maxRetryPerUrl && currentAttemptUrl != null) {
            Log.d(TAG, "Retrying URL[$currentUrlIndex] attempt $retryCount/$maxRetryPerUrl")
            postDelayed(800L) {
                playResolvedUrl(currentAttemptUrl)
            }
            return
        }

        // 当前URL已达重试上限 → 切换到下一个URL（对应APK的urlIndex+1）
        retryCount = 0
        val nextIndex = findNextAvailableUrlIndex()
        if (nextIndex >= 0) {
            currentUrlIndex = nextIndex
            val nextUrl = currentAlternativeUrls[nextIndex]
            Log.i(TAG, "Switching to URL[$nextIndex]: $nextUrl")
            onUrlSwitched?.invoke(nextUrl)
            postDelayed(300L) {
                playResolvedUrl(nextUrl)
            }
        } else {
            Log.e(TAG, "All ${currentAlternativeUrls.size} URLs failed for channel")
            onError?.invoke(
                "播放失败：已尝试 ${currentAlternativeUrls.size} 个地址均无法连接。\n" +
                "可能原因：当前网络对该直播源存在屏蔽，或所有源地址均已失效。\n" +
                "建议：刷新直播源，或切换到其他频道。"
            )
        }
    }

    /** 查找下一个未失败的备选URL索引 */
    private fun findNextAvailableUrlIndex(): Int {
        for (i in currentAlternativeUrls.indices) {
            if (i == currentUrlIndex) continue
            val url = currentAlternativeUrls[i]
            if (url !in failedUrls) return i
        }
        return -1
    }

    /**
     * 播放指定频道（按APK的完整播放流程）
     *
     * 流程：
     * 1. 获取频道所有URL（保留协议前缀）
     * 2. 为每个URL生成镜像（UrlHelper.getStreamAlternativeUrls）
     * 3. 从第一个URL开始播放
     * 4. 失败 → urlIndex+1 → 下一个URL
     */
    fun play(channel: Channel) {
        val allUrls = channel.getAllUrls()
        play(channel.url, allUrls.drop(1))
    }

    /**
     * 播放指定频道（支持多URL自动降级）
     *
     * @param url 频道主URL（可能包含协议前缀：sys_http://, ikkHeaders://等）
     * @param backupUrls 备用URL列表（同样可能包含协议前缀）
     */
    fun play(url: String, backupUrls: List<String> = emptyList()) {
        currentUrl = url
        retryCount = 0
        failedUrls.clear()
        currentUrlIndex = 0

        // 构建完整候选URL列表
        // 注意：APK协议前缀（sys_, ikkHeaders://等）在此阶段保留
        // 实际解析在 playResolvedUrl() 中进行（对应APK的setVideoPath逻辑）
        val urlList = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun addCandidate(candidate: String) {
            if (candidate.isNotBlank() && seen.add(candidate.lowercase())) {
                urlList.add(candidate)
            }
        }

        // 主URL + 镜像
        addCandidate(url)
        UrlHelper.getStreamAlternativeUrls(url, emptyList()).forEach { addCandidate(it) }

        // 备用URL
        for (backup in backupUrls) {
            if (backup != url) {
                addCandidate(backup)
                UrlHelper.getStreamAlternativeUrls(backup, emptyList()).forEach { addCandidate(it) }
            }
        }

        currentAlternativeUrls = urlList

        Log.i(TAG, "Starting playback with ${currentAlternativeUrls.size} candidate URLs")
        currentAlternativeUrls.forEachIndexed { i, u ->
            Log.d(TAG, "  [$i] $u")
        }

        val player = player ?: createPlayer().also { player = it }
        playResolvedUrl(currentAlternativeUrls.first())
    }

    /**
     * 播放单个已解析的URL（按APK的IjkVideoView.setVideoPath逻辑）
     *
     * 这是核心方法，完全按照APK的协议解析和播放器分流逻辑：
     * 1. Channel.resolveForPlayback() 解析协议前缀
     * 2. sys_ → 系统播放器（绕DNS缓存）
     * 3. ikkHeaders:// → 注入自定义Headers
     * 4. 创建对应的DataSource和MediaSource
     */
    private fun playResolvedUrl(url: String) {
        Log.d(TAG, "playResolvedUrl: $url")

        // ========== 1. 解析协议（按APK的setVideoPath逻辑）==========
        // 需要一个临时Channel对象来调用resolveForPlayback
        val resolved = resolveUrlForPlayback(url)

        val actualUrl = resolved.url
        currentHeaders = resolved.headers
        isSysPlayer = resolved.useSystemPlayer

        if (resolved.headers.isNotEmpty()) {
            Log.d(TAG, "Custom headers: ${resolved.headers}")
        }
        Log.d(TAG, "System player: $isSysPlayer, URL: $actualUrl")

        // ========== 2. 清理DNS缓存（对应APK的dns_cache_clear=1）==========
        // 系统播放器自动走系统DNS，无需手动清理
        // ExoPlayer通过OkHttp的DNS配置实现

        // ========== 3. 创建播放器和MediaSource ==========
        val player = player ?: createPlayer().also { player = it }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(actualUrl))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.02f)
                    .build()
            )
            .build()

        val mediaSource = createMediaSource(actualUrl, mediaItem, resolved.headers, resolved.useSystemPlayer)
        player.setMediaSource(mediaSource)
        player.prepare()
    }

    /**
     * 解析URL的协议前缀（按APK的IjkVideoView.setVideoPath逻辑）
     *
     * 这是独立的静态方法，不依赖Channel对象
     */
    private fun resolveUrlForPlayback(url: String): ResolvedUrl {
        var playUrl = url
        var useSystemPlayer = false
        var useIjkPlayer = false
        val headers = mutableMapOf<String, String>()

        // ========== 1. sys_ 前缀 → 系统播放器（绕DNS缓存）==========
        if (playUrl.startsWith("sys_")) {
            useSystemPlayer = true
            playUrl = playUrl.removePrefix("sys_")
        }

        // ========== 2. ikk:// / koo:// → IJK播放器 ==========
        if (playUrl.startsWith("ikk://") || playUrl.startsWith("koo://")) {
            useIjkPlayer = true
            val innerUrl = extractQueryParam(playUrl, "url")
            if (innerUrl != null) {
                playUrl = innerUrl
            }
        }

        // ========== 3. ikkHeaders:// / kooHeaders:// / Headers:// ==========
        val lower = playUrl.lowercase()
        if (lower.startsWith("ikkheaders://") || lower.startsWith("kooheaders://") || lower.startsWith("headers://")) {
            val parsed = parseHeadersUrl(playUrl)
            if (parsed != null) {
                playUrl = parsed.first
                headers.putAll(parsed.second)
            }
        }

        return ResolvedUrl(
            url = playUrl,
            useSystemPlayer = useSystemPlayer,
            useIjkPlayer = useIjkPlayer,
            headers = headers
        )
    }

    /** 从URL查询参数中提取指定参数值 */
    private fun extractQueryParam(url: String, key: String): String? {
        return try {
            val qIndex = url.indexOf('?')
            if (qIndex < 0) return null
            url.substring(qIndex + 1)
                .split("&")
                .find { it.startsWith("$key=") }
                ?.substring(key.length + 1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        } catch (e: Exception) { null }
    }

    /** 解析Headers URL：ikkHeaders://?url=X&Referer=Y&Origin=Z */
    private fun parseHeadersUrl(url: String): Pair<String, Map<String, String>>? {
        return try {
            val qIndex = url.indexOf('?')
            if (qIndex < 0) return null

            val params = url.substring(qIndex + 1)
                .split("&")
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq > 0) {
                        val k = java.net.URLDecoder.decode(part.substring(0, eq), "UTF-8")
                        val v = java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                        k to v
                    } else null
                }
                .toMap()

            val actualUrl = params["url"] ?: return null
            val headers = params.filterKeys { it != "url" }
            Pair(actualUrl, headers)
        } catch (e: Exception) { null }
    }

    /**
     * 创建DataSource.Factory
     *
     * 关键区分（按APK的播放器分流逻辑）：
     * - 系统播放器(sys_) → DefaultHttpDataSource.Factory（走系统DNS，绕IJK缓存）
     * - 默认播放器 → OkHttpDataSource.Factory（桌面UA + 自定义DNS + SafeDns）
     */
    private fun createDataSourceFactory(
        headers: Map<String, String> = emptyMap(),
        useSystemPlayer: Boolean = false
    ): DefaultDataSource.Factory {
        if (useSystemPlayer) {
            // 系统播放器：使用DefaultHttpDataSource，走系统DNS
            // 对应APK中sys_前缀 → AndroidMediaPlayer
            val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(HttpClientProvider.DESKTOP_USER_AGENT)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)

            if (headers.isNotEmpty()) {
                httpFactory.setDefaultRequestProperties(headers)
            }

            return DefaultDataSource.Factory(context, httpFactory)
        }

        // 默认播放器：使用OkHttpDataSource + 桌面UA + 自定义DNS
        // 对应APK中默认 → IjkMediaPlayer + 自定义Headers
        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(HttpClientProvider.DESKTOP_USER_AGENT)

        if (headers.isNotEmpty()) {
            okHttpFactory.setDefaultRequestProperties(headers)
        }

        return DefaultDataSource.Factory(context, okHttpFactory)
    }

    /**
     * 创建MediaSource（HLS或默认）
     *
     * 包含反屏蔽的LoadErrorHandlingPolicy：
     * - manifest重试5次（对应APK的reconnect=3）
     * - media重试3次
     * - 失败后由handlePlayerError切换到下一个URL
     */
    private fun createMediaSource(
        url: String,
        mediaItem: MediaItem,
        headers: Map<String, String> = emptyMap(),
        useSystemPlayer: Boolean = false
    ): MediaSource {
        val dataSourceFactory = createDataSourceFactory(headers, useSystemPlayer)

        val DATA_TYPE_MANIFEST = 1
        val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(loadType: Int): Int =
                if (loadType == DATA_TYPE_MANIFEST) 5 else 3
        }

        return if (url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy)
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy)
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

    fun pause() { player?.pause() }
    fun resume() { player?.play() }

    // ==================== 音量控制 ====================

    fun volumeUp() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    }

    fun volumeDown() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    fun toggleMute(): Boolean {
        if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            return false
        } else {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            return true
        }
    }

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
