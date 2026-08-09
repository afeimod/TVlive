package com.tvlive.app.data

import android.content.Context
import android.util.Log
import com.tvlive.app.data.db.TvLiveDatabase
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.ChannelGroup
import com.tvlive.app.data.model.PlayHistory
import com.tvlive.app.data.model.Source
import com.tvlive.app.net.HttpClientProvider
import com.tvlive.app.net.ISPDetector
import com.tvlive.app.net.UrlHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 频道数据仓库 - 负责数据获取、解析、缓存
 */
class ChannelRepository(private val context: Context) {

    private val db = TvLiveDatabase.get(context)
    private val channelDao = db.channelDao()
    private val sourceDao = db.sourceDao()
    private val historyDao = db.historyDao()

    private val httpClient = HttpClientProvider.dataClient

    val allChannels: Flow<List<Channel>> = channelDao.getAllChannels()
    val favorites: Flow<List<Channel>> = channelDao.getFavorites()
    val allSources: Flow<List<Source>> = sourceDao.getAllSources()
    val groups: Flow<List<String>> = channelDao.getGroups()
    val history: Flow<List<PlayHistory>> = historyDao.getAll()

    // ==================== 源管理 ====================

    /**
     * 初始化默认源
     *
     * 支持版本迁移：当 DefaultSources.VERSION 变化时，会清除旧默认源并重新插入
     *
     * v5 升级特殊处理：v5 将默认源精简为单一 iptv-org 源（github.io 版本），
     * 需要清理 v3/v4 时期预置的多余源（zbds、fanmingming、大壮哥哥等）
     */
    suspend fun initDefaultSources() {
        val prefs = context.getSharedPreferences("tvlive_prefs", Context.MODE_PRIVATE)
        val savedVersion = prefs.getInt("sources_version", 0)

        if (sourceDao.count() == 0) {
            // 首次安装：插入所有默认源
            DefaultSources.sources.forEach { source ->
                val id = sourceDao.insert(source)
                Log.d("ChannelRepository", "Inserted source: ${source.name} id=$id")
            }
            prefs.edit().putInt("sources_version", DefaultSources.VERSION).apply()
        } else if (savedVersion < DefaultSources.VERSION) {
            // 版本升级
            Log.i("ChannelRepository", "Upgrading sources from v$savedVersion to v${DefaultSources.VERSION}")

            // v5 升级：清理旧版本预置的多余源
            // 仅清理已知内置源名称，保留用户自定义源
            val legacySourceNames = setOf(
                "iptv-org 中国频道",
                "iptv-org 全球频道",
                "zbds 每日更新源",
                "fanmingming 直播源",
                "fanmingming 直播源(IPv4)",
                "fanmingming 直播源(IPv6)",
                "joevess 央视卫视源",
                "yuanzl77 国内直播源",
                "Free-TV 全球免费",
                "Collect-IPTV 精选合集",
                "大壮哥哥 live TV"
            )
            val existingSources = sourceDao.getAllSourcesList()
            for (existing in existingSources) {
                if (existing.name in legacySourceNames) {
                    channelDao.deleteBySource(existing.id)
                    sourceDao.delete(existing)
                    Log.i("ChannelRepository", "Removed legacy source: ${existing.name}")
                }
            }

            // 插入/更新当前版本的默认源
            val currentSources = sourceDao.getAllSourcesList()
            for (defaultSource in DefaultSources.sources) {
                val existing = currentSources.find { it.name == defaultSource.name }
                if (existing != null) {
                    // 更新已有源的 URL
                    if (existing.url != defaultSource.url) {
                        sourceDao.update(existing.copy(
                            url = defaultSource.url,
                            isDefault = defaultSource.isDefault,
                            enabled = defaultSource.enabled
                        ))
                        Log.i("ChannelRepository", "Updated source URL: ${defaultSource.name} -> ${defaultSource.url}")
                    }
                } else {
                    // 新增源
                    val id = sourceDao.insert(defaultSource)
                    Log.d("ChannelRepository", "Inserted new source: ${defaultSource.name} id=$id")
                }
            }

            // 重置 sources_loaded 标志，让应用重新刷新源
            prefs.edit()
                .putInt("sources_version", DefaultSources.VERSION)
                .putBoolean("sources_loaded", false)
                .apply()
        }
    }

    suspend fun addSource(name: String, url: String): Long {
        val source = Source(name = name, url = url)
        return sourceDao.insert(source)
    }

    suspend fun updateSource(source: Source) = sourceDao.update(source)

    suspend fun deleteSource(source: Source) {
        channelDao.deleteBySource(source.id)
        sourceDao.delete(source)
    }

    suspend fun setDefaultSource(id: Long) {
        sourceDao.clearDefault()
        sourceDao.setDefault(id)
    }

    suspend fun getEnabledSources(): List<Source> = sourceDao.getEnabledSources()

    // ==================== 频道加载 ====================

    /**
     * 从网络加载所有启用的源并解析频道
     *
     * 中国移动 5G 网络深度优化策略（v2）：
     *
     * 1. 并行加载所有启用的源（每个源 8s 超时）
     * 2. **关键变更**：无论网络源是否成功，都把内置备用频道追加到列表末尾
     *    这样即使 iptv-org 加载成功但其流地址被屏蔽，用户也能切换到内置频道播放
     * 3. 内置备用频道使用真实可用的中国移动 IPTV IPv6 URL（来自 yuanzl77/IPTV）
     *    在移动 5G/4G/宽带网络内可直接访问
     * 4. 同名频道会被合并（保留多 URL），播放器自动降级尝试
     */
    suspend fun refreshAllSources(onProgress: ((current: Int, total: Int, sourceName: String) -> Unit)? = null): RefreshResult {
        val sources = getEnabledSources()
        val totalChannels = java.util.concurrent.atomic.AtomicInteger(0)
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)

        channelDao.deleteAll()

        onProgress?.invoke(0, sources.size, "正在加载...")

        // 并行加载所有源，每个源完成后立即插入数据库
        coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    try {
                        val rawData = fetchUrlBytes(source.url)
                        val channels = if (ApkChannelParser.isApkData(rawData)) {
                            // APK 加密频道数据：gzip + JSON + AES
                            val ispTag = when (ISPDetector.currentISP) {
                                ISPDetector.ISPType.CMCC -> "Y"
                                ISPDetector.ISPType.TELECOM -> "D"
                                ISPDetector.ISPType.UNICOM -> "L"
                                else -> null
                            }
                            ApkChannelParser.parse(rawData, source, ispTag)
                        } else {
                            // 标准 M3U 格式
                            val content = String(rawData, Charsets.UTF_8)
                            M3UParser.parse(content, source)
                        }

                        if (channels.isNotEmpty()) {
                            val channelsWithNumber = channels.mapIndexed { i, ch ->
                                ch.copy(channelNumber = i + 1)
                            }
                            channelDao.insertAll(channelsWithNumber)
                            totalChannels.addAndGet(channels.size)
                            successCount.incrementAndGet()
                            sourceDao.update(source.copy(
                                lastUpdate = System.currentTimeMillis(),
                                channelCount = channels.size
                            ))
                            Log.d("ChannelRepository", "Loaded ${channels.size} channels from ${source.name}")
                        } else {
                            failCount.incrementAndGet()
                            errors.add("${source.name}: 解析到0个频道")
                        }
                    } catch (e: Exception) {
                        failCount.incrementAndGet()
                        errors.add("${source.name}: ${e.message}")
                        Log.e("ChannelRepository", "Failed to load source ${source.name}", e)
                    }

                    val done = completedCount.incrementAndGet()
                    onProgress?.invoke(done, sources.size, source.name)
                }
            }.awaitAll()
        }

        val sc = successCount.get()
        val fc = failCount.get()

        // 关键策略：始终追加内置备用频道（即使网络源成功也追加）
        // 原因：iptv-org 的 cn.m3u 包含许多被中国移动屏蔽的境外 CDN 流（69.x, 74.91.x, 198.204.x 等北美 IP），
        // 网络源"加载成功"不代表"流能播放"。内置频道使用真实可用的移动 IPTV IPv6 URL，作为可靠备份。
        Log.i("ChannelRepository", "Appending fallback channels (network sources loaded $sc/${sources.size})")
        val fallbackChannels = loadFallbackChannels()
        if (fallbackChannels.isNotEmpty()) {
            val existingNames = channelDao.getAllNames().toSet()
            // 只插入网络源中没有的频道，避免重复
            val newFallback = fallbackChannels.filter { it.name !in existingNames }
            if (newFallback.isNotEmpty()) {
                channelDao.insertAll(newFallback)
                Log.i("ChannelRepository", "Appended ${newFallback.size} fallback channels (skipped ${fallbackChannels.size - newFallback.size} duplicates)")
            }
            // 全部网络源失败时，所有备用频道都会被插入
            if (sc == 0 && newFallback.isEmpty() && fallbackChannels.isNotEmpty()) {
                channelDao.insertAll(fallbackChannels)
                Log.i("ChannelRepository", "All network sources failed, all ${fallbackChannels.size} fallback channels loaded")
            }
        }

        val finalTotalInDb = channelDao.count()
        val finalErrors = if (sc == 0 && fallbackChannels.isNotEmpty()) {
            errors + "网络源全部失败，已加载内置备用频道"
        } else {
            errors.toList()
        }
        return RefreshResult(
            successCount = if (sc == 0 && fallbackChannels.isNotEmpty()) 1 else sc,
            failCount = fc,
            totalChannels = finalTotalInDb,
            errors = finalErrors
        )
    }

    /**
     * 从 assets 加载内置备用频道
     * 当所有网络源都失败时（如中国移动网络封锁），确保用户仍能观看基本频道
     */
    private suspend fun loadFallbackChannels(): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val content = context.assets.open("fallback_channels.m3u").bufferedReader().use { it.readText() }
            val fallbackSource = Source(id = -1, name = "内置备用频道", url = "local://fallback")
            val channels = M3UParser.parse(content, fallbackSource)
            Log.i("ChannelRepository", "Loaded ${channels.size} fallback channels from assets")
            channels.mapIndexed { i, ch -> ch.copy(channelNumber = i + 1) }
        } catch (e: Exception) {
            Log.e("ChannelRepository", "Failed to load fallback channels", e)
            emptyList()
        }
    }

    /**
     * 仅加载指定源
     */
    suspend fun refreshSource(source: Source): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val rawData = fetchUrlBytes(source.url)
            val channels = if (ApkChannelParser.isApkData(rawData)) {
                ApkChannelParser.parse(rawData, source)
            } else {
                M3UParser.parse(String(rawData, Charsets.UTF_8), source)
            }
            channelDao.deleteBySource(source.id)
            val channelsWithNumber = channels.mapIndexed { i, ch ->
                ch.copy(channelNumber = i + 1)
            }
            channelDao.insertAll(channelsWithNumber)
            sourceDao.update(source.copy(
                lastUpdate = System.currentTimeMillis(),
                channelCount = channels.size
            ))
            channelsWithNumber
        } catch (e: Exception) {
            Log.e("ChannelRepository", "Failed to refresh source ${source.name}", e)
            emptyList()
        }
    }

    /**
     * 并行获取 URL 内容为字节数组（5G 网络优化关键）
     *
     * 支持 APK 的 gzip 二进制数据 和 M3U 文本数据
     * 同时发起所有镜像 URL 的请求，第一个成功的响应即返回，其余请求自动取消。
     *
     * @param url 原始 URL（会被 UrlHelper 转换为多个镜像 URL）
     * @return 第一个成功响应的原始字节
     */
    private suspend fun fetchUrlBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val alternativeUrls = UrlHelper.getAlternativeUrls(url)
        Log.i("ChannelRepository", "Parallel fetching ${alternativeUrls.size} URLs for: $url")

        val result = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        val supervisorJob = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(supervisorJob + Dispatchers.IO)

        val jobs = alternativeUrls.map { attemptUrl ->
            scope.async<Unit> {
                try {
                    Log.d("ChannelRepository", "Fetching: $attemptUrl")
                    val request = Request.Builder()
                        .url(attemptUrl)
                        .header("User-Agent", HttpClientProvider.USER_AGENT)
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw RuntimeException("HTTP ${response.code}")
                        }
                        val body = response.body?.bytes()
                        if (body == null || body.isEmpty()) {
                            throw RuntimeException("空响应")
                        }
                        Log.i("ChannelRepository", "✓ Success: $attemptUrl (${body.size} bytes)")
                        result.complete(body)
                    }
                } catch (e: Exception) {
                    Log.w("ChannelRepository", "✗ Failed: $attemptUrl: ${e.message}")
                    // 不抛出异常，让其他镜像继续尝试
                }
            }
        }

        // 监控所有 job，全部完成时检查是否没有任何成功
        val watchdog = scope.async<Unit> {
            try {
                jobs.awaitAll()
            } catch (_: Exception) { }
            // 所有 job 都完成，若 result 仍未完成，说明全部失败
            if (!result.isCompleted) {
                result.completeExceptionally(
                    RuntimeException("所有 ${alternativeUrls.size} 个镜像 URL 均无法访问: $url")
                )
            }
        }

        try {
            result.await()
        } finally {
            // 取消所有未完成的请求和 watchdog
            supervisorJob.cancel()
            watchdog.cancel()
        }
    }

    // ==================== 频道操作 ====================

    fun getChannelsByGroup(group: String): Flow<List<Channel>> = channelDao.getChannelsByGroup(group)

    fun searchChannels(keyword: String): Flow<List<Channel>> = channelDao.search(keyword)

    suspend fun toggleFavorite(channel: Channel) {
        channelDao.setFavorite(channel.id, !channel.favorite)
    }

    suspend fun setFavorite(channel: Channel, fav: Boolean) {
        channelDao.setFavorite(channel.id, fav)
    }

    /**
     * 获取按分组的所有频道
     */
    fun getGroupedChannels(): Flow<List<ChannelGroup>> = flow {
        val allChannelsList = channelDao.getAllChannels()
        allChannelsList.collect { channels ->
            val grouped = channels.groupBy { it.group }
                .map { (name, chs) -> ChannelGroup(name, chs.toMutableList()) }
                .sortedBy { groupOrder(it.name) }
            emit(grouped)
        }
    }.flowOn(Dispatchers.IO)

    private fun groupOrder(name: String): Int = when (name) {
        Channel.GROUP_CCTV -> 0
        Channel.GROUP_SATELLITE -> 1
        Channel.GROUP_HK_MACAO_TW -> 2
        Channel.GROUP_LOCAL -> 3
        Channel.GROUP_INTERNATIONAL -> 4
        Channel.GROUP_OTHER -> 5
        else -> 6
    }

    // ==================== 历史记录 ====================

    suspend fun addHistory(channel: Channel) {
        historyDao.deleteByChannel(channel.id)
        historyDao.insert(
            PlayHistory(
                channelId = channel.id,
                channelName = channel.name,
                channelUrl = channel.url
            )
        )
        historyDao.trimOld()
    }

    suspend fun clearHistory() = historyDao.deleteAll()

    suspend fun getChannelCount(): Int = channelDao.count()
}

data class RefreshResult(
    val successCount: Int,
    val failCount: Int,
    val totalChannels: Int,
    val errors: List<String>
)
