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
 *
 * 中国移动网络优化：
 * - 同一频道名在 M3U 中出现多次时（许多 IPTV 聚合源常见做法），
 *   自动合并为单条 Channel，主 URL 取第一条，其余 URL 作为 backupUrls
 * - 这样播放失败时可以自动尝试下一个 URL，提高可用性
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

        val rawChannels = if (isM3U) parseM3U(content, source) else parseTxt(content, source)

        // 合并同名频道（不同 URL 作为 backupUrls）
        return mergeSameNameChannels(rawChannels)
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
                // 部分聚合源用 | 分隔多个 URL，全部作为该频道的 URL 候选
                val urlVariants = trimmed.split("|").map { it.trim() }.filter { it.isNotBlank() }
                if (currentName.isNotBlank() && urlVariants.isNotEmpty()) {
                    val finalGroup = classifyGroup(currentGroup, currentName)
                    val primaryUrl = urlVariants.first()
                    val backups = if (urlVariants.size > 1) urlVariants.drop(1).joinToString("|") else ""

                    channels.add(
                        Channel(
                            name = currentName,
                            url = primaryUrl,
                            logo = currentLogo,
                            group = finalGroup,
                            tvgId = currentTvgId,
                            tvgName = currentTvgName ?: currentName,
                            sourceId = source.id,
                            backupUrls = backups
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
     * 合并同名频道：将多个同名条目的 URL 合并为第一条的 backupUrls
     *
     * 许多 IPTV 聚合源为同一频道提供多个流地址（不同清晰度/CDN/备份），
     * 合并后播放器可按顺序自动尝试，遇到被中国移动网络屏蔽的 URL 时
     * 自动降级到下一个，显著提高播放成功率
     *
     * 中国移动网络优化：
     * - URL 排序优先级：国内域名 > 中国移动 IPTV IPv6 > 国内 IP > 其他
     * - 已知被屏蔽的境外 IP（北美 69.x, 74.91.x, 198.204.x 等）排到最后
     *   这样播放器优先尝试可达的 URL，失败再尝试不可达的，提升首播成功率
     */
    private fun mergeSameNameChannels(channels: List<Channel>): List<Channel> {
        if (channels.isEmpty()) return channels

        val merged = mutableListOf<Channel>()
        val nameToIndex = mutableMapOf<String, Int>()
        // 用小写 + 去空格做归一化匹配
        fun normalize(s: String) = s.lowercase().replace("\\s+".toRegex(), "").trim()

        for (ch in channels) {
            val key = normalize(ch.name)
            val existingIdx = nameToIndex[key]
            if (existingIdx == null) {
                merged.add(ch)
                nameToIndex[key] = merged.size - 1
            } else {
                val existing = merged[existingIdx]
                // 收集所有 URL（existing + 当前），按可达性排序后重新组织
                val allUrls = buildList {
                    add(existing.url)
                    if (existing.backupUrls.isNotBlank()) addAll(existing.getBackupUrlList())
                    if (ch.url != existing.url) add(ch.url)
                    addAll(ch.getBackupUrlList())
                }.distinct()

                // 按可达性排序：国内域名/移动 IPTV IPv6 优先，已知屏蔽 IP 排后
                val sortedUrls = allUrls.sortedWith(compareBy { urlRank(it) })
                val primaryUrl = sortedUrls.first()
                val backups = sortedUrls.drop(1).filter { it != primaryUrl }

                merged[existingIdx] = existing.copy(
                    url = primaryUrl,
                    backupUrls = backups.joinToString("|")
                )
            }
        }

        return merged
    }

    /**
     * URL 可达性排序：值越小优先级越高
     *
     * 中国移动 5G 网络下的可达性优先级（参考 APK 的 ISP 标签策略）：
     * 0. 中国移动 IPTV IPv6（[2409:8087...]）- 移动网络内必达
     * 1. 国内域名（.cn / .com.cn / 国内 CDN 域名）- 通常可达
     * 2. 国内 IP 段（39.13x, 112.x, 117.x, 183.x, 223.x 等）- 通常可达
     * 3. 其他未识别 URL - 中性
     * 4. 已知被屏蔽的境外 IP（69.x, 74.91.x, 198.204.x, 192.151.x 等北美 IPTV 服务器）- 必定屏蔽
     *
     * ISP 感知增强：
     * - 对于移动网络用户，增加更多国内 CDN 域名识别
     * - 对于电信/联通用户，境外 CDN 可能可达（但此函数不处理 ISP 差异，由 UrlHelper 处理）
     */
    private fun urlRank(url: String): Int {
        val lower = url.lowercase()
        return when {
            // 中国移动 IPTV IPv6 - 最高优先级
            lower.contains("2409:8087") || lower.contains("[2409:") -> 0
            // 国内域名（扩充：增加更多已知的国内 CDN/流媒体域名）
            lower.contains(".cn/") || lower.contains(".cn:") || lower.endsWith(".cn") ||
            lower.contains("chinamobile.com") || lower.contains("voc.com.cn") ||
            lower.contains("cctv.com") || lower.contains(".edu.cn") ||
            lower.contains("pdtvhd.com") || lower.contains("eac-news.com") ||
            lower.contains("itv.cmcc.cn") || lower.contains("ott.cibntv.net") ||
            lower.contains("bestv.com.cn") || lower.contains("kankanlive.com") ||
            lower.contains("juyun.tv") || lower.contains("bread-tv.com") -> 1
            // 已知被屏蔽的境外 IPTV 服务器（北美）- 参考 APK 中被移动网络屏蔽的 IP 段
            lower.startsWith("http://69.") || lower.startsWith("http://74.91.") ||
            lower.startsWith("http://198.204.") || lower.startsWith("http://192.151.") ||
            lower.startsWith("http://23.") || lower.startsWith("http://45.") ||
            lower.startsWith("http://104.") || lower.startsWith("http://162.") -> 4
            // 国内 IP 段（中国移动/电信/联通）
            lower.startsWith("http://39.1") || lower.startsWith("http://39.134.") ||
            lower.startsWith("http://39.135.") || lower.startsWith("http://112.") ||
            lower.startsWith("http://117.") || lower.startsWith("http://118.") ||
            lower.startsWith("http://121.") || lower.startsWith("http://122.") ||
            lower.startsWith("http://123.") || lower.startsWith("http://183.") ||
            lower.startsWith("http://218.") || lower.startsWith("http://222.") ||
            lower.startsWith("http://223.") -> 2
            // 其他
            else -> 3
        }
    }

    /**
     * 智能分类频道到标准分组
     *
     * 增强版：更好地处理 iptv-org cn.m3u 中的频道名称
     * - 支持 "CCTV-1 (1080p)" 等带清晰度标记的名称
     * - 支持 "BRTV 北京卫视" 等带前缀的名称
     * - 支持更多国际频道识别
     */
    private fun classifyGroup(originalGroup: String, name: String): String {
        // 优先使用原始分组（如果匹配标准分类）
        val g = originalGroup.lowercase()
        when {
            g.contains("cctv") || g.contains("央视") || g.contains("中央") ||
            g.contains("news") && g.contains("cctv") -> return Channel.GROUP_CCTV
            g.contains("卫视") || g.contains("satellite") -> return Channel.GROUP_SATELLITE
            g.contains("港") || g.contains("澳") || g.contains("台") || g.contains("hk") ||
            g.contains("macao") || g.contains("taiwan") -> return Channel.GROUP_HK_MACAO_TW
            g.contains("国际") || g.contains("international") || g.contains("world") -> return Channel.GROUP_INTERNATIONAL
            g.contains("地方") || g.contains("local") || g.contains("provincial") -> return Channel.GROUP_LOCAL
        }

        // 根据频道名智能判断
        return when {
            name.contains("CCTV", true) || name.contains("央视") || name.contains("中央") -> Channel.GROUP_CCTV
            name.contains("卫视") -> Channel.GROUP_SATELLITE
            name.contains("香港", true) || name.contains("TVB", true) || name.contains("澳门") ||
            name.contains("台湾") || name.contains("中视") || name.contains("华视") ||
            name.contains("民视") || name.contains("龙华") || name.contains("凤凰") ||
            name.contains("HK", true) && name.contains("TV") -> Channel.GROUP_HK_MACAO_TW
            name.contains("NHK", true) || name.contains("BBC", true) || name.contains("CNN", true) ||
            name.contains("ARIRANG", true) || name.contains("DW", true) || name.contains("France", true) ||
            name.contains("RT", true) || name.contains("Sky", true) || name.contains("FOX", true) ||
            name.contains("Al Jazeera", true) || name.contains("CGTN", true) ||
            name.contains("KBS", true) || name.contains("MBC", true) ||
            name.contains("Euro", true) || name.contains("ABC", true) ||
            name.contains("NBN", true) || name.contains("ABN", true) -> Channel.GROUP_INTERNATIONAL
            // 宗教/少儿/音乐等归类为"其他"
            name.contains("Religious", true) || name.contains("宗教") ||
            name.contains("Kids", true) || name.contains("Cartoon", true) ||
            name.contains("Music", true) || name.contains("Bread", true) -> Channel.GROUP_OTHER
            else -> Channel.GROUP_LOCAL
        }
    }
}
