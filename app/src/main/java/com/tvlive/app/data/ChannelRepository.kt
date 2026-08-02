package com.tvlive.app.data

import android.content.Context
import android.util.Log
import com.tvlive.app.data.db.TvLiveDatabase
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.ChannelGroup
import com.tvlive.app.data.model.PlayHistory
import com.tvlive.app.data.model.Source
import com.tvlive.app.network.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 频道数据仓库 - 负责数据获取、解析、缓存
 *
 * 优先从 assets 本地源加载, 不依赖在线刷新
 */
class ChannelRepository(private val context: Context) {

    private val db = TvLiveDatabase.get(context)
    private val channelDao = db.channelDao()
    private val sourceDao = db.sourceDao()
    private val historyDao = db.historyDao()

    private val httpClient = NetworkConfig.createClient(context).build()

    val allChannels: Flow<List<Channel>> = channelDao.getAllChannels()
    val favorites: Flow<List<Channel>> = channelDao.getFavorites()
    val allSources: Flow<List<Source>> = sourceDao.getAllSources()
    val groups: Flow<List<String>> = channelDao.getGroups()
    val history: Flow<List<PlayHistory>> = historyDao.getAll()

    companion object {
        private const val ASSET_SOURCE_NAME = DefaultSources.LOCAL_SOURCE_NAME
        private const val ASSET_FILE = "china_channels.m3u"
    }

    // ==================== 源管理 ====================

    /**
     * 初始化默认源
     * 本地离线源为默认主源 (不需要网络即可加载)
     * 在线国内直连源为备用 (网络可用时获取更多频道)
     */
    suspend fun initDefaultSources() {
        // 1. 本地离线源 (默认主源, 不需要网络)
        if (sourceDao.getByName(ASSET_SOURCE_NAME) == null) {
            val localSource = Source(
                name = ASSET_SOURCE_NAME,
                url = "assets://$ASSET_FILE",
                isDefault = true,
                enabled = true
            )
            sourceDao.insert(localSource)
            Log.d("ChannelRepository", "Inserted local source (default): $ASSET_SOURCE_NAME")
        }

        // 2. 在线国内直连源 (备用, zbds.top 不经过GitHub)
        if (sourceDao.getByName(DefaultSources.ONLINE_SOURCE_NAME) == null &&
            sourceDao.getByUrl(DefaultSources.ONLINE_SOURCE_URL) == null) {
            val onlineSource = Source(
                name = DefaultSources.ONLINE_SOURCE_NAME,
                url = DefaultSources.ONLINE_SOURCE_URL,
                isDefault = false,
                enabled = true
            )
            sourceDao.insert(onlineSource)
            Log.d("ChannelRepository", "Inserted online source: ${DefaultSources.ONLINE_SOURCE_NAME}")
        }

        // 3. 清除可能残留的旧版GitHub源 (URL包含githubusercontent)
        val allSources = sourceDao.getAllSourcesStatic()
        allSources.forEach { source ->
            if (source.url.contains("githubusercontent.com") ||
                source.url.contains("raw.githubusercontent.com") ||
                source.url.contains("gh-proxy.com") ||
                source.url.contains("ghfast.top")) {
                Log.d("ChannelRepository", "Removing old GitHub source: ${source.name}")
                channelDao.deleteBySource(source.id)
                sourceDao.delete(source)
            }
        }

        // 4. 确保至少有一个默认源
        val enabled = sourceDao.getEnabledSources()
        if (enabled.none { it.isDefault } && enabled.isNotEmpty()) {
            sourceDao.clearDefault()
            sourceDao.setDefault(enabled.first().id)
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

    suspend fun getEnabledSources(): List<Source> {
        initDefaultSources()
        return sourceDao.getEnabledSources()
    }

    // ==================== 频道加载 ====================

    /**
     * 刷新所有启用的源
     * 本地源 (assets://) 直接读取, 在线源走网络
     */
    suspend fun refreshAllSources(onProgress: ((current: Int, total: Int, sourceName: String) -> Unit)? = null): RefreshResult {
        initDefaultSources()

        val sources = getEnabledSources().sortedBy { source ->
            // 本地源优先 (assets:// 排在前面), 在线源排后面
            if (source.url.startsWith("assets://")) 0 else 1
        }
        var totalChannels = 0
        var successCount = 0
        var failCount = 0
        val errors = mutableListOf<String>()

        channelDao.deleteAll()

        sources.forEachIndexed { index, source ->
            onProgress?.invoke(index + 1, sources.size, source.name)
            try {
                val content = if (source.url.startsWith("assets://")) {
                    // 本地 assets 源
                    loadFromAssets(source.url.removePrefix("assets://"))
                } else {
                    // 在线源 (带代理回退)
                    fetchUrlWithFallback(source.url)
                }

                val channels = M3UParser.parse(content, source)

                if (channels.isNotEmpty()) {
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
            } catch (e: Exception) {
                failCount++
                errors.add("${source.name}: ${e.message}")
                Log.e("ChannelRepository", "Failed to load source ${source.name}", e)
            }
        }

        return RefreshResult(successCount, failCount, totalChannels, errors)
    }

    /**
     * 从 assets 读取本地源文件
     */
    private fun loadFromAssets(fileName: String): String {
        return context.assets.open(fileName).bufferedReader().use { it.readText() }
    }

    /**
     * 仅加载指定源
     */
    suspend fun refreshSource(source: Source): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val content = if (source.url.startsWith("assets://")) {
                loadFromAssets(source.url.removePrefix("assets://"))
            } else {
                fetchUrlWithFallback(source.url)
            }
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
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkConfig.USER_AGENT)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}")
            }
            response.body?.string() ?: throw RuntimeException("空响应")
        }
    }

    /**
     * 带回退的URL获取
     * 在线源失败时自动回退到本地 assets 源
     */
    private suspend fun fetchUrlWithFallback(primaryUrl: String): String = withContext(Dispatchers.IO) {
        try {
            fetchUrl(primaryUrl)
        } catch (e: Exception) {
            Log.w("ChannelRepository", "在线源失败, 尝试本地源: ${e.message}")
            // 在线源失败, 回退到本地 assets 源
            try {
                loadFromAssets(ASSET_FILE)
            } catch (e2: Exception) {
                Log.e("ChannelRepository", "本地源也失败", e2)
                throw e  // 返回原始错误
            }
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
