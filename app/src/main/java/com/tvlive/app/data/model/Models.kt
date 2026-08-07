package com.tvlive.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * 频道数据模型
 *
 * url: 主播放 URL
 * backupUrls: 备用 URL 列表（来自 M3U 中同一频道的多 URL，用 | 分隔存储）
 *             播放失败时按顺序尝试，提高在中国移动网络下的可用性
 */
@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String = "未分类",
    val tvgId: String? = null,
    val tvgName: String? = null,
    val sourceId: Long = 0,
    var favorite: Boolean = false,
    var channelNumber: Int = 0,
    val backupUrls: String = ""
) {
    /** 获取备用 URL 列表（按 | 分隔解析） */
    fun getBackupUrlList(): List<String> =
        if (backupUrls.isBlank()) emptyList()
        else backupUrls.split("|").map { it.trim() }.filter { it.isNotBlank() }
    companion object {
        const val GROUP_CCTV = "央视"
        const val GROUP_SATELLITE = "卫视"
        const val GROUP_LOCAL = "地方"
        const val GROUP_HK_MACAO_TW = "港澳台"
        const val GROUP_INTERNATIONAL = "国际"
        const val GROUP_OTHER = "其他"
    }

    /** 智能分类：根据频道名判断所属分组 */
    fun autoGroup(): String {
        val n = name.lowercase()
        return when {
            n.contains("cctv") || n.contains("央视") || n.contains("中央") -> GROUP_CCTV
            n.contains("卫视") -> GROUP_SATELLITE
            n.contains("香港") || n.contains("tvb") || n.contains("澳门") ||
            n.contains("台湾") || n.contains("中视") || n.contains("华视") ||
            n.contains("民视") -> GROUP_HK_MACAO_TW
            n.contains("nhk") || n.contains("bbc") || n.contains("cnn") ||
            n.contains("arirang") || n.contains("dw") || n.contains("france") ||
            n.contains("rt ") || n.contains("sky") || n.contains("fox") -> GROUP_INTERNATIONAL
            else -> GROUP_LOCAL
        }
    }
}

/**
 * 频道分组
 */
data class ChannelGroup(
    val name: String,
    val channels: MutableList<Channel> = mutableListOf()
)

/**
 * 直播源
 */
@Entity(tableName = "sources")
data class Source(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val lastUpdate: Long = 0,
    val channelCount: Int = 0
)

/**
 * EPG 节目信息
 */
data class EpgProgram(
    @SerializedName("title")
    val title: String,
    @SerializedName("start")
    val start: Long,
    @SerializedName("stop")
    val stop: Long,
    @SerializedName("desc")
    val desc: String? = null
)

/**
 * 播放历史
 */
@Entity(tableName = "history")
data class PlayHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val channelId: Long,
    val channelName: String,
    val channelUrl: String,
    val watchTime: Long = System.currentTimeMillis()
)
