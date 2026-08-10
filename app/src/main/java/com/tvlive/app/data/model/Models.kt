package com.tvlive.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * 频道数据模型（v3 - 按APK运行时逻辑设计）
 *
 * 关键变更（参考APK的Channel.java）：
 * - urls: 按ISP过滤后的可播放URL列表（主URL + 备用，用|分隔）
 * - urlIndex: 当前正在播放的URL索引（播放失败时自动+1切换下一个）
 * - 播放器分流由URL前缀决定：
 *   sys_ → 系统播放器（绕DNS缓存/DNS污染）
 *   ikk:// → IJK播放器
 *   ikkHeaders:// → IJK播放器+自定义Headers
 *   其他 → 默认播放器
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
    val backupUrls: String = "",
    /** 频道类型（参考APK的ctype）：3=央视, 6=购物, 9=卫视, 27=地方, 210=超清 */
    val ctype: Int = -1
) {
    /**
     * 获取所有可播放URL列表（按优先级排序）
     * 格式：url | backup1 | backup2 | ...
     *
     * 每个URL可能包含APK协议前缀：
     * - sys_http:// → 用系统播放器
     * - ikkHeaders://?url=...&Referer=... → 注入Headers
     * - http(s):// → 直接播放
     */
    fun getAllUrls(): List<String> {
        val all = mutableListOf(url)
        all.addAll(getBackupUrlList())
        return all
    }

    /** 获取备用 URL 列表（按 | 分隔解析） */
    fun getBackupUrlList(): List<String> =
        if (backupUrls.isBlank()) emptyList()
        else backupUrls.split("|").map { it.trim() }.filter { it.isNotBlank() }

    /**
     * 判断URL应使用系统播放器（参考APK的isSysPlayer逻辑）
     * sys_ 前缀 → Android系统MediaPlayer（绕过IJK DNS缓存，防DNS污染）
     */
    fun shouldUseSystemPlayer(url: String): Boolean {
        return url.startsWith("sys_")
    }

    /**
     * 判断URL需要自定义Headers（参考APK的ikkHeaders://协议）
     */
    fun shouldUseCustomHeaders(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("ikkheaders://") ||
               lower.startsWith("kooheaders://") ||
               lower.startsWith("headers://")
    }

    /**
     * 解析URL为实际可播放地址 + 播放器类型 + 自定义Headers
     *
     * 完全按照APK的 IjkVideoView.setVideoPath() 逻辑实现
     */
    fun resolveForPlayback(url: String): ResolvedUrl {
        var playUrl = url
        var useSystemPlayer = false
        var useIjkPlayer = false
        val headers = mutableMapOf<String, String>()

        // ========== 1. sys_ 前缀 → 系统播放器 ==========
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

        // ========== 3. ikkHeaders:// / kooHeaders:// / Headers:// → Headers注入 ==========
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

    /** 从URL查询参数中提取指定参数 */
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

    companion object {
        const val GROUP_CCTV = "央视频道"
        const val GROUP_SATELLITE = "卫视频道"
        const val GROUP_SHOPPING = "购物频道"
        const val GROUP_UHD = "超清频道"
        const val GROUP_LOCAL = "地方频道"
        // 地方子分组（省份）由ctype动态生成，不再硬编码
        const val GROUP_HK_MACAO_TW = "港澳台"
        const val GROUP_INTERNATIONAL = "国际"
        const val GROUP_OTHER = "其他"

        /** 占位符/导视频道名称（不是真正可播放的频道，需过滤） */
        val FILLER_NAMES = setOf(
            "导视精选", "热门推荐", "好物推荐", "居家好物",
            "导视", "推荐", "精选"
        )

        /** 判断是否为占位符频道 */
        fun isFillerChannel(name: String): Boolean {
            return FILLER_NAMES.any { name.contains(it) }
        }
    }

    /**
     * 按APK ctype分类（ctype是APK的一级分组键）
     *
     * APK分类体系（完全按types数组）：
     * - ctype=3  → 央视频道（固定Tab）
     * - ctype=6  → 购物频道（固定Tab）
     * - ctype=9  → 卫视频道（固定Tab）
     * - ctype=210 → 超清频道
     * - ctype=27  → 地方频道（虚拟父组，实际频道在子ctype中）
     * - ctype=30~258 → 地方子分组（省份，ptype=27）
     */
    fun classifyByCtype(province: String): String {
        return when (ctype) {
            3 -> GROUP_CCTV
            6 -> GROUP_SHOPPING
            9 -> GROUP_SATELLITE
            210 -> GROUP_UHD
            27 -> GROUP_LOCAL
            in 30..258 -> {
                // 地方子分组 → 使用省份名（去掉"地区"后缀）
                // 例："广东地区" → "广东"
                val p = province.replace("地区", "").trim()
                if (p.isNotBlank()) p else GROUP_LOCAL
            }
            else -> autoGroup()  // 非APK源走智能分类
        }
    }

    /** 智能分类：根据频道名判断所属分组（用于M3U等非APK源） */
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
 * 解析后的播放URL（参考APK的IjkVideoView.setVideoPath()逻辑）
 */
data class ResolvedUrl(
    /** 实际可播放的HTTP/HTTPS URL */
    val url: String,
    /** 是否使用系统播放器（sys_前缀 → 绕DNS缓存） */
    val useSystemPlayer: Boolean = false,
    /** 是否强制使用IJK播放器 */
    val useIjkPlayer: Boolean = false,
    /** 自定义HTTP请求头（ikkHeaders://协议注入） */
    val headers: Map<String, String> = emptyMap()
)

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
