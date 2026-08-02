package com.tvlive.app.data

import android.content.Context
import android.util.Log
import com.tvlive.app.data.db.TvLiveDatabase
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.ChannelGroup
import com.tvlive.app.data.model.PlayHistory
import com.tvlive.app.data.model.Source
import com.tvlive.app.net.HttpClientProvider
import com.tvlive.app.net.UrlHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
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
            // 版本升级：更新默认源的 URL（保留用户自定义源）
            Log.i("ChannelRepository", "Upgrading sources from v$savedVersion to v${DefaultSources.VERSION}")
            val existingSources = sourceDao.getAllSourcesList()

            for (defaultSource in DefaultSources.sources) {
                val existing = existingSources.find { it.name == defaultSource.name }
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
     * 使用并行加载：所有源同时请求，先完成的先插入数据库，让 UI 尽快显示频道
     * 如果所有网络源都失败（如中国移动网络封锁），会自动加载内置备用频道
     */
    suspend fun refreshAllSources(onProgress: ((current: Int, total: Int, sourceName: String) -> Unit)? = null): RefreshResult {
        val sources = getEnabledSources()
        var totalChannels = 0
        var successCount = 0
        var failCount = 0
        val errors = mutableListOf<String>()

        channelDao.deleteAll()

        onProgress?.invoke(0, sources.size, "正在加载...")

        // 并行加载所有源
        val results = kotlinx.coroutines.coroutineScope {
            sources.map { source ->
                kotlinx.coroutines.async(Dispatchers.IO) {
                    try {
                        val content = fetchUrl(source.url)
                        val channels = M3UParser.parse(content, source)
                        Triple(source, channels, null as Exception?)
                    } catch (e: Exception) {
                        Triple(source, emptyList<Channel>(), e)
                    }
                }
            }.awaitAll()
        }

        // 按顺序处理结果（先成功的先插入）
        results.forEachIndexed { index, (source, channels, error) ->
            onProgress?.invoke(index + 1, sources.size, source.name)
            if (error != null) {
                failCount++
                errors.add("${source.name}: ${error.message}")
                Log.e("ChannelRepository", "Failed to load source ${source.name}", error)
            } else if (channels.isNotEmpty()) {
                val channelsWithNumber = channels.mapIndexed { i, ch ->
                    ch.copy(channelNumber = i + 1)
                }
                channelDao.insertAll(channelsWithNumber)
                totalChannels += channels.size
                successCount++
                sourceDao.update(source.copy(
                    lastUpdate = System.currentTimeMillis(),
                    channelCount = channels.size
                ))
                Log.d("ChannelRepository", "Loaded ${channels.size} channels from ${source.name}")
            } else {
                failCount++
                errors.add("${source.name}: 解析到0个频道")
            }
        }

        // 所有网络源都失败时，加载内置备用频道（兜底）
        if (successCount == 0) {
            Log.w("ChannelRepository", "All network sources failed, loading fallback channels from assets")
            val fallbackChannels = loadFallbackChannels()
            if (fallbackChannels.isNotEmpty()) {
                channelDao.insertAll(fallbackChannels)
                totalChannels = fallbackChannels.size
                successCount = 1  // 标记为部分成功，让 UI 能继续播放
                errors.add("网络源全部失败，已加载内置备用频道")
            }
        }

        return RefreshResult(successCount, failCount, totalChannels, errors)
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
            val content = fetchUrl(source.url)
            val channels = M3UParser.parse(content, source)
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

    private suspend fun fetchUrl(url: String): String = withContext(Dispatchers.IO) {
        // 获取原始 URL + 镜像备选 URL 列表
        val alternativeUrls = UrlHelper.getAlternativeUrls(url)
        var lastError: Exception? = null

        for (attemptUrl in alternativeUrls) {
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
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        throw RuntimeException("空响应")
                    }
                    return@withContext body
                }
            } catch (e: Exception) {
                lastError = e
                Log.w("ChannelRepository", "Fetch failed for $attemptUrl: ${e.message}")
                // 继续尝试下一个备选 URL
            }
        }

        throw lastError ?: RuntimeException("所有 URL 均无法访问: $url")
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
