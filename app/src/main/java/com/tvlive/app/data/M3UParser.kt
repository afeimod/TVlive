package com.tvlive.app.data

import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.Source

/**
 * M3U / M3U8 播放列表解析器
 *
 * 支持标准 M3U 格式：
 * #EXTM3U
 * #EXTINF:-1 tvg-id="..." tvg-name="..." tvg-logo="..." group-title="...",频道名称
 * http://...
 *
 * 也支持简单 TXT 格式：
 * 频道名称,http://...
 */
object M3UParser {

    private val EXTINF_REGEX = Regex(
        """#EXTINF:-1\s*(?:tvg-id="([^"]*)")?\s*(?:tvg-name="([^"]*)")?\s*(?:tvg-logo="([^"]*)")?\s*(?:group-title="([^"]*)")?,?(.*)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 解析 M3U 文本内容
     */
    fun parse(content: String, source: Source): List<Channel> {
        if (content.isBlank()) return emptyList()

        // 检测格式
        val isM3U = content.trim().startsWith("#EXTM3U", ignoreCase = true)

        return if (isM3U) parseM3U(content, source) else parseTxt(content, source)
    }

    private fun parseM3U(content: String, source: Source): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()

        var currentName = ""
        var currentLogo: String? = null
        var currentGroup = ""
        var currentTvgId: String? = null
        var currentTvgName: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF", ignoreCase = true)) {
                // 解析 EXTINF 行
                val match = EXTINF_REGEX.find(trimmed)
                if (match != null) {
                    currentTvgId = match.groupValues[1].ifBlank { null }
                    currentTvgName = match.groupValues[2].ifBlank { null }
                    currentLogo = match.groupValues[3].ifBlank { null }
                    currentGroup = match.groupValues[4].ifBlank { "" }
                    currentName = match.groupValues[5].trim()
                } else {
                    // 兜底：取逗号后的名称
                    currentName = trimmed.substringAfterLast(",").trim()
                }
            } else if (trimmed.startsWith("#EXTGRP:", ignoreCase = true)) {
                currentGroup = trimmed.substringAfter(":").trim()
            } else if (!trimmed.startsWith("#")) {
                // 这是 URL 行
                if (currentName.isNotBlank()) {
                    val finalGroup = classifyGroup(currentGroup, currentName)
                    channels.add(
                        Channel(
                            name = currentName,
                            url = trimmed,
                            logo = currentLogo,
                            group = finalGroup,
                            tvgId = currentTvgId,
                            tvgName = currentTvgName ?: currentName,
                            sourceId = source.id
                        )
                    )
                }
                // 重置
                currentName = ""
                currentLogo = null
                currentGroup = ""
                currentTvgId = null
                currentTvgName = null
            }
        }

        return channels
    }

    private fun parseTxt(content: String, source: Source): List<Channel> {
        val channels = mutableListOf<Channel>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // 格式: 频道名称,URL  或  频道名称#URL
            val parts = if (trimmed.contains(",")) {
                val commaIdx = trimmed.indexOf(",")
                Pair(trimmed.substring(0, commaIdx).trim(), trimmed.substring(commaIdx + 1).trim())
            } else if (trimmed.contains("#")) {
                val hashIdx = trimmed.indexOf("#")
                Pair(trimmed.substring(0, hashIdx).trim(), trimmed.substring(hashIdx + 1).trim())
            } else {
                continue
            }

            val name = parts.first
            val url = parts.second
            if (name.isNotBlank() && (url.startsWith("http") || url.startsWith("rtmp"))) {
                channels.add(
                    Channel(
                        name = name,
                        url = url,
                        group = classifyGroup("", name),
                        tvgName = name,
                        sourceId = source.id
                    )
                )
            }
        }
        return channels
    }

    /**
     * 智能分类频道到标准分组
     */
    private fun classifyGroup(originalGroup: String, name: String): String {
        // 优先使用原始分组（如果匹配标准分类）
        val g = originalGroup.lowercase()
        when {
            g.contains("cctv") || g.contains("央视") || g.contains("中央") -> return Channel.GROUP_CCTV
            g.contains("卫视") || g.contains("satellite") -> return Channel.GROUP_SATELLITE
            g.contains("港") || g.contains("澳") || g.contains("台") || g.contains("hk") ||
            g.contains("macao") || g.contains("taiwan") -> return Channel.GROUP_HK_MACAO_TW
            g.contains("国际") || g.contains("international") || g.contains("world") -> return Channel.GROUP_INTERNATIONAL
        }

        // 根据频道名智能判断
        return when {
            name.contains("CCTV", true) || name.contains("央视") || name.contains("中央") -> Channel.GROUP_CCTV
            name.contains("卫视") -> Channel.GROUP_SATELLITE
            name.contains("香港", true) || name.contains("TVB", true) || name.contains("澳门") ||
            name.contains("台湾") || name.contains("中视") || name.contains("华视") ||
            name.contains("民视") || name.contains("龙华") -> Channel.GROUP_HK_MACAO_TW
            name.contains("NHK", true) || name.contains("BBC", true) || name.contains("CNN", true) ||
            name.contains("ARIRANG", true) || name.contains("DW", true) || name.contains("France", true) ||
            name.contains("RT", true) || name.contains("Sky", true) || name.contains("FOX", true) ||
            name.contains("Al Jazeera", true) -> Channel.GROUP_INTERNATIONAL
            else -> Channel.GROUP_LOCAL
        }
    }
}
