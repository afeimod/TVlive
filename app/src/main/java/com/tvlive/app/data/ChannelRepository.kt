package com.tvlive.app.data

import android.content.Context
import android.util.Log
import com.tvlive.app.data.db.TvLiveDatabase
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.ChannelGroup
import com.tvlive.app.data.model.PlayHistory
import com.tvlive.app.data.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 频道数据仓库 - 负责数据获取、解析、缓存
 */
class ChannelRepository(private val context: Context) {

    private val db = TvLiveDatabase.get(context)
    private val channelDao = db.channelDao()
    private val sourceDao = db.sourceDao()
    private val historyDao = db.historyDao()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val allChannels: Flow<List<Channel>> = channelDao.getAllChannels()
    val favorites: Flow<List<Channel>> = channelDao.getFavorites()
    val allSources: Flow<List<Source>> = sourceDao.getAllSources()
    val groups: Flow<List<String>> = channelDao.getGroups()
    val history: Flow<List<PlayHistory>> = historyDao.getAll()

    // ==================== 源管理 ====================

    /** 初始化默认源 */
    suspend fun initDefaultSources() {
        if (sourceDao.count() == 0) {
            DefaultSources.sources.forEach { source ->
                val id = sourceDao.insert(source)
                Log.d("ChannelRepository", "Inserted source: ${source.name} id=$id")
            }
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
     */
    suspend fun refreshAllSources(onProgress: ((current: Int, total: Int, sourceName: String) -> Unit)? = null): RefreshResult {
        val sources = getEnabledSources()
        var totalChannels = 0
        var successCount = 0
        var failCount = 0
        val errors = mutableListOf<String>()

        channelDao.deleteAll()

        sources.forEachIndexed { index, source ->
            onProgress?.invoke(index + 1, sources.size, source.name)
            try {
                val content = fetchUrl(source.url)
                val channels = M3UParser.parse(content, source)

                if (channels.isNotEmpty()) {
                    // 分配频道号
                    val channelsWithNumber = channels.mapIndexed { i, ch ->
                        ch.copy(channelNumber = i + 1)
                    }
                    channelDao.insertAll(channelsWithNumber)
                    totalChannels += channels.size
                    successCount++

                    // 更新源信息
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
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}")
            }
            response.body?.string() ?: throw RuntimeException("空响应")
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
