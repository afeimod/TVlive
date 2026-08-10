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
 * 基于 Media3 ExoPlayer 的电视直播播放器管理（v4 - 完全按APK运行时逻辑）
 *
 * ★★★ 核心逻辑（与APK的IjkVideoView + LiveActivity完全一致）★★★
 *
 * 1. **播放器分流**（APK的IjkVideoView.setVideoPath逻辑）：
 *    - sys_ 前缀 → 系统播放器（DefaultHttpDataSource，走系统DNS）
 *      对应APK中 sys_ → AndroidMediaPlayer（绕IJK DNS缓存）
 *    - ikk:// / koo:// → 提取内部URL（APK的ParseUrlUtil.parser逻辑）
 *    - ikkHeaders:// / kooHeaders:// / Headers:// → 注入Headers
 *      对应APK中 ParseUrlUtil.parserHeadersUrl + setVideoURI(uri, headers)
 *    - 其他 → 默认播放器（OkHttpDataSource + 桌面UA + SafeDns）
 *
 * 2. **DNS缓存清除**（对应APK的 dns_cache_clear=1）：
 *    - 每次播放新URL前，清空OkHttp连接池和DNS缓存
 *    - 防止被污染的DNS结果被复用（中国移动DNS污染核心对策）
 *    - sys_ 前缀额外使用 DefaultHttpDataSource（走系统DNS，彻底绕OkHttp缓存）
 *
 * 3. **自动重连**（对应APK的 reconnect=3）：
 *    - 单URL播放失败后重试最多3次（与APK的reconnect=3一致）
 *    - 3次均失败后切换到下一个URL（urlIndex+1）
 *
 * 4. **错误自动重试**（对应APK的 LiveActivity.onError/onCompletion）：
 *    - 播放错误 → 1秒后重试（APK: this.f.b(1000L)）
 *    - 直播流结束 → 2秒后重试（APK: this.f.a(2000L)）
 *    - 重试前清DNS缓存
 *
 * 5. **URL索引切换**（对应APK的Channel.urlIndex + j.b()/j.w()逻辑）：
 *    - 每个频道有多个URL（urls列表）
 *    - 播放失败 → urlIndex+1，尝试下一个URL
 *    - 用户手动切源 → urlIndex+1（循环）
 */
class TvPlayerManager(private val context: Context) {

    var player: ExoPlayer? = null
        private set

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 当前频道的主URL */
    private var currentUrl: String? = null

    /** 当前频道的所有备选URL列表（按优先级排序，保留协议前缀） */
    private var currentAlternativeUrls: List<String> = emptyList()

    /** 当前正在尝试的URL索引（对应APK的Channel.urlIndex） */
    private var currentUrlIndex = 0

    /** 当前URL的重试次数 */
    private var retryCount = 0

    /** 单个URL最大重试次数（对应APK的reconnect=3） */
    private val maxRetryPerUrl = 3

    /** 当前会话内已知失败的URL（避免重复尝试） */
    private val failedUrls = mutableSetOf<String>()

    /** 当前播放URL对应的自定义Headers（ikkHeaders://协议注入） */
    private var currentHeaders: Map<String, String> = emptyMap()

    /** 当前是否使用系统播放器（sys_前缀 → true） */
    private var isSysPlayer = false

    /** 是否正在重试中（防止重试嵌套） */
    private var isRetrying = false

    /** 直播流结束重试次数（APK: onCompletion → retry(2s)，最多3次） */
    private var completionRetryCount = 0
    private val maxCompletionRetry = 3

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

        // 5G/移动网络优化：加快直播起播速度（与APK的IJK参数对应）
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
                            Player.STATE_BUFFERING -> {
                                isRetrying = false
                                onLoading?.invoke()
                            }
                            Player.STATE_READY -> {
                                retryCount = 0
                                completionRetryCount = 0
                                isRetrying = false
                                onReady?.invoke()
                            }
                            Player.STATE_ENDED -> {
                                // ★★★ 直播流结束 → 2秒后重试（对应APK: onCompletion → this.f.a(2000L)）
                                // 直播流不应该结束，如果结束了说明连接断开，需要重连
                                handleStreamEnded()
                            }
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
     * ★★★ 直播流结束处理（对应APK的LiveActivity.onCompletion → this.f.a(2000L)）★★★
     *
     * 直播流不应该自然结束。如果STATE_ENDED触发，说明：
     * 1. 服务端主动断开连接（CDN调度/负载均衡）
     * 2. 网络中断后恢复（移动网络切换/5G↔4G）
     * 3. 服务端推送了新流（频道切换/广告插播）
     *
     * APK的处理：onCompletion → 2秒后重试同一个URL
     */
    private fun handleStreamEnded() {
        if (completionRetryCount >= maxCompletionRetry) {
            Log.w(TAG, "Stream ended $completionRetryCount times, switching to next URL")
            switchToNextUrl()
            return
        }

        completionRetryCount++
        val currentAttemptUrl = currentAlternativeUrls.getOrNull(currentUrlIndex) ?: currentUrl
        Log.i(TAG, "Stream ended, retrying in 2s (attempt $completionRetryCount/$maxCompletionRetry): $currentAttemptUrl")

        // ★ 清DNS缓存后重试（对应APK: dns_cache_clear=1）
        clearDnsCache()

        postDelayed(2000L) {
            currentAttemptUrl?.let { playResolvedUrl(it) }
        }
    }

    /**
     * ★★★ DNS缓存清除（对应APK的 dns_cache_clear=1）★★★
     *
     * IJK Player的 dns_cache_clear=1 会在每次播放前清空FFmpeg的DNS缓存。
     * ExoPlayer没有此选项，但我们可以通过以下方式实现等效效果：
     *
     * 1. 驱逐OkHttp连接池中的所有空闲连接（清除缓存的DNS结果）
     * 2. 让下一次DNS查询走全新的解析路径
     *
     * 这是防止中国移动DNS污染被复用的关键机制。
     */
    private fun clearDnsCache() {
        try {
            // 清空播放器连接池（驱遣所有空闲连接，DNS缓存随之失效）
            HttpClientProvider.evictPlayerConnections()
            Log.d(TAG, "DNS cache cleared (evicted player connection pool)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear DNS cache: ${e.message}")
        }

        // 也清空数据请求连接池（防止源加载时的DNS污染影响播放）
        try {
            HttpClientProvider.evictDataConnections()
        } catch (_: Exception) {}
    }

    /**
     * 处理播放错误：当前URL重试或切换到下一个备选URL
     * ★★★ 对应APK的 LiveActivity.onError → this.f.b(1000L) → urlIndex+1 ★★★
     */
    private fun handlePlayerError(error: PlaybackException) {
        if (isRetrying) return  // 防止重试嵌套
        isRetrying = true

        val currentAttemptUrl = currentAlternativeUrls.getOrNull(currentUrlIndex) ?: currentUrl
        Log.w(TAG, "Player error on URL[$currentUrlIndex]: $currentAttemptUrl - ${error.errorCodeName}")

        currentAttemptUrl?.let { failedUrls.add(it) }

        retryCount++
        if (retryCount <= maxRetryPerUrl && currentAttemptUrl != null) {
            // ★★★ 单URL重试（对应APK: onError → retry after 1s）★★★
            Log.d(TAG, "Retrying URL[$currentUrlIndex] attempt $retryCount/$maxRetryPerUrl in 1s")

            // ★ 清DNS缓存后重试（对应APK: dns_cache_clear=1）
            clearDnsCache()

            postDelayed(1000L) {
                playResolvedUrl(currentAttemptUrl)
            }
            return
        }

        // 当前URL已达重试上限 → 切换到下一个URL（对应APK的urlIndex+1）
        switchToNextUrl()
    }

    /**
     * 切换到下一个备选URL（对应APK的 j.b() — urlIndex++）
     */
    private fun switchToNextUrl() {
        retryCount = 0
        isRetrying = false

        val nextIndex = findNextAvailableUrlIndex()
        if (nextIndex >= 0) {
            currentUrlIndex = nextIndex
            val nextUrl = currentAlternativeUrls[nextIndex]
            Log.i(TAG, "Switching to URL[$nextIndex]: $nextUrl")
            onUrlSwitched?.invoke(nextUrl)

            // ★ 清DNS缓存后切换（对应APK: dns_cache_clear=1）
            clearDnsCache()

            postDelayed(300L) {
                playResolvedUrl(nextUrl)
            }
        } else {
            Log.e(TAG, "All ${currentAlternativeUrls.size} URLs failed for channel")
            isRetrying = false
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
     * ★★★ 用户手动切换URL线路（对应APK的遥控器 S1/S2 键 → srcIndex → urlIndex++）★★★
     *
     * APK中用户可以通过遥控器切换当前频道的不同URL线路（同一频道的不同CDN源）
     */
    fun switchUrlLine(): String? {
        if (currentAlternativeUrls.size <= 1) return null

        // 重置失败记录，允许重新尝试之前失败的URL
        failedUrls.clear()
        retryCount = 0
        completionRetryCount = 0

        // 循环切换到下一个URL
        currentUrlIndex = (currentUrlIndex + 1) % currentAlternativeUrls.size
        val nextUrl = currentAlternativeUrls[currentUrlIndex]
        Log.i(TAG, "Manual URL line switch to [$currentUrlIndex]: $nextUrl")
        onUrlSwitched?.invoke(nextUrl)

        // ★ 清DNS缓存后切换
        clearDnsCache()

        // 重建播放器（确保完全清空状态）
        player?.stop()
        playResolvedUrl(nextUrl)

        return nextUrl
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
        completionRetryCount = 0
        failedUrls.clear()
        currentUrlIndex = 0
        isRetrying = false

        // ★★★ 清DNS缓存（对应APK: dns_cache_clear=1，每次新频道播放前清除）★★★
        clearDnsCache()

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

        Log.i(TAG, "Starting playback with ${currentAlternativeUrls.size} candidate URLs (ISP: ${ISPDetector.currentISP.label})")
        currentAlternativeUrls.forEachIndexed { i, u ->
            Log.d(TAG, "  [$i] $u")
        }

        val player = player ?: createPlayer().also { player = it }
        playResolvedUrl(currentAlternativeUrls.first())
    }

    /**
     * ★★★ 播放单个已解析的URL（完全按APK的IjkVideoView.setVideoPath逻辑）★★★
     *
     * 这是核心方法，完全按照APK的协议解析和播放器分流逻辑：
     * 1. 解析协议前缀（sys_, ikk://, ikkHeaders://等）
     * 2. sys_ → 系统播放器（DefaultHttpDataSource，走系统DNS，绕OkHttp缓存）
     * 3. ikkHeaders:// → 提取URL + 注入自定义Headers
     * 4. 创建对应的DataSource和MediaSource
     * 5. ★ 每次播放前清DNS缓存（dns_cache_clear=1）
     */
    private fun playResolvedUrl(url: String) {
        Log.d(TAG, "playResolvedUrl: $url")

        // ========== 1. 解析协议（按APK的setVideoPath逻辑）==========
        val resolved = resolveUrlForPlayback(url)

        val actualUrl = resolved.url
        currentHeaders = resolved.headers
        isSysPlayer = resolved.useSystemPlayer

        if (resolved.headers.isNotEmpty()) {
            Log.d(TAG, "Custom headers: ${resolved.headers}")
        }
        Log.d(TAG, "System player: $isSysPlayer, URL: $actualUrl")

        // ========== 2. 清DNS缓存（对应APK的dns_cache_clear=1）==========
        // 每次播放新URL都清DNS缓存，防止DNS污染被复用
        clearDnsCache()

        // ========== 3. 停止当前播放并重新准备 ==========
        // 对应APK: IjkVideoView.openVideo() → release(false) → createPlayer() → setDataSource → prepareAsync()
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

        // ★ 对应APK: 设置新数据源前先停止当前播放
        player.stop()
        player.setMediaSource(mediaSource)
        player.prepare()
    }

    /**
     * ★★★ 解析URL的协议前缀（完全按APK的IjkVideoView.setVideoPath逻辑）★★★
     *
     * APK的setVideoPath()逻辑：
     * 1. ParseUrlUtil.clear()
     * 2. isSysPlayer = false, isIkkPlayer = false
     * 3. if startsWith("sys_") → isSysPlayer = true, str = str.replace("sys_", "")
     * 4. else if startsWith("ikk://") || startsWith("koo://") → isIkkPlayer = true, ParseUrlUtil.parser(str), str = urldecode(strUrlParas.get("url"))
     * 5. if startsWith("kooHeaders://") || startsWith("ikkHeaders://") || startsWith("Headers://") → ParseUrlUtil.parserHeadersUrl(str), str = strUrlHeaders.get("url"), setVideoURI(uri, headers)
     */
    private fun resolveUrlForPlayback(url: String): ResolvedUrl {
        var playUrl = url
        var useSystemPlayer = false
        var useIjkPlayer = false
        val headers = mutableMapOf<String, String>()

        // ========== 1. sys_ 前缀 → 系统播放器（绕DNS缓存）==========
        // 对应APK: if (str.startsWith("sys_")) { this.isSysPlayer = true; str = str.replace("sys_", ""); }
        if (playUrl.startsWith("sys_")) {
            useSystemPlayer = true
            playUrl = playUrl.removePrefix("sys_")
        }

        // ========== 2. ikk:// / koo:// → IJK播放器 ==========
        // 对应APK: else if (str.startsWith("ikk://") || str.startsWith("koo://")) { isIkkPlayer = true; ParseUrlUtil.parser(str); ... }
        if (playUrl.startsWith("ikk://") || playUrl.startsWith("koo://")) {
            useIjkPlayer = true
            val innerUrl = extractQueryParam(playUrl, "url")
            if (innerUrl != null) {
                playUrl = innerUrl
            }
        }

        // ========== 3. ikkHeaders:// / kooHeaders:// / Headers:// → Headers注入 ==========
        // 对应APK: if (!str.startsWith("kooHeaders://") && !str.startsWith("ikkHeaders://") && !str.startsWith("Headers://")) { setVideoURI(Uri.parse(str)); return; }
        //           ParseUrlUtil.parserHeadersUrl(str); String str3 = strUrlHeaders.get("url"); strUrlHeaders.remove("url"); setVideoURI(Uri.parse(str3), strUrlHeaders);
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

    /** 从URL查询参数中提取指定参数值（对应APK的ParseUrlUtil.parser + urldecode） */
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

    /**
     * 解析Headers URL（对应APK的ParseUrlUtil.parserHeadersUrl）
     *
     * 格式：ikkHeaders://?url=X&Referer=Y&Origin=Z
     * 或：kooHeaders://?url=X&Referer=Y
     * 或：Headers://?url=X&Referer=Y&Origin=Z
     */
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
     * ★★★ 创建DataSource.Factory（按APK的播放器分流逻辑）★★★
     *
     * 关键区分：
     * - 系统播放器(sys_) → DefaultHttpDataSource.Factory（走系统DNS，绕OkHttp缓存）
     *   对应APK: sys_ → AndroidMediaPlayer → 系统DNS解析
     * - 默认播放器 → OkHttpDataSource.Factory（桌面UA + SafeDns + 反屏蔽拦截器）
     *   对应APK: 默认 → IjkMediaPlayer + 自定义Headers
     */
    private fun createDataSourceFactory(
        headers: Map<String, String> = emptyMap(),
        useSystemPlayer: Boolean = false
    ): DefaultDataSource.Factory {
        if (useSystemPlayer) {
            // ★★★ 系统播放器：使用DefaultHttpDataSource，走系统DNS ★★★
            // 对应APK中 sys_ 前缀 → AndroidMediaPlayer
            // DefaultHttpDataSource 使用 java.net.HttpURLConnection → 系统 DNS 解析
            // 完全绕过 OkHttp 的 DNS 缓存，防止被污染的 DNS 结果被复用
            val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(HttpClientProvider.DESKTOP_USER_AGENT)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true)

            if (headers.isNotEmpty()) {
                httpFactory.setDefaultRequestProperties(headers)
            } else {
                // 即使没有自定义headers，也注入基础反屏蔽headers
                // 对应APK: IjkVideoView.setVideoURI(uri, headers) 即使普通URL也会有默认headers
                httpFactory.setDefaultRequestProperties(mapOf(
                    "User-Agent" to HttpClientProvider.DESKTOP_USER_AGENT
                ))
            }

            return DefaultDataSource.Factory(context, httpFactory)
        }

        // ★★★ 默认播放器：使用OkHttpDataSource + 桌面UA + SafeDns + 反屏蔽 ★★★
        // 对应APK: IjkMediaPlayer + 自定义Headers + dns_cache_clear=1 + reconnect=3
        // 注意：每次播放前已调用clearDnsCache()清空连接池
        val okClient = HttpClientProvider.playerClient
        val okHttpFactory = OkHttpDataSource.Factory(okClient)
            .setUserAgent(HttpClientProvider.DESKTOP_USER_AGENT)

        if (headers.isNotEmpty()) {
            okHttpFactory.setDefaultRequestProperties(headers)
        }

        return DefaultDataSource.Factory(context, okHttpFactory)
    }

    /**
     * ★★★ 创建MediaSource（HLS或默认）★★★
     *
     * 包含反屏蔽的LoadErrorHandlingPolicy（对应APK的reconnect=3）：
     * - manifest重试6次（比APK的3次更激进，因为ExoPlayer和IJK的错误处理语义不同）
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

        // ★★★ LoadErrorHandlingPolicy（对应APK: reconnect=3 + dns_cache_clear=1）★★★
        // ExoPlayer的LoadErrorHandlingPolicy控制的是单个Load的重试
        // 而APK的reconnect=3控制的是整个连接的重试
        // 两者语义不同，这里设置更激进的重试策略
        val DATA_TYPE_MANIFEST = 1
        val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(loadType: Int): Int =
                if (loadType == DATA_TYPE_MANIFEST) 6 else 3

            override fun getRetryDelayMsFor(loadErrorInfo: DefaultLoadErrorHandlingPolicy.LoadErrorInfo): Long {
                // ★ 重试延迟递增：1s, 2s, 3s...（对应APK: 1s后重试）
                return (loadErrorInfo.errorCount * 1000L).coerceIn(1000L, 5000L)
            }
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
        completionRetryCount = 0
        failedUrls.clear()
        isRetrying = false
    }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }

    /** 获取当前URL信息（用于UI显示） */
    fun getCurrentUrlInfo(): String {
        val url = currentAlternativeUrls.getOrNull(currentUrlIndex) ?: return ""
        val resolved = resolveUrlForPlayback(url)

        // 识别CDN类型
        val cdnType = when {
            resolved.url.contains("miguvideo.com") -> "咪咕"
            resolved.url.contains("video.qq.com") -> "腾讯"
            resolved.url.contains("douyincdn.com") -> "抖音"
            resolved.url.contains("cctv.cn") || resolved.url.contains("cntv.cn") -> "CCTV"
            resolved.url.contains("cmcc.cn") || resolved.url.contains("chinamobile") -> "移动IPTV"
            resolved.useSystemPlayer -> "系统播放"
            else -> "备用源"
        }

        return "[${currentUrlIndex + 1}/${currentAlternativeUrls.size}] $cdnType"
    }

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
