package com.tvlive.app.net

import android.util.Log

/**
 * URL 镜像工具
 *
 * 解决两类被中国移动网络封锁的 URL：
 *
 * 1. **M3U 播放列表 URL**（加载源时）
 *    - GitHub raw / jsdelivr 等被 DNS 污染或 IP 封锁的域名
 *    - 通过 jsDelivr CDN、GitHub 代理、清华镜像等绕过
 *
 * 2. **流媒体 URL**（播放电视台时）
 *    - 中国移动 IPTV IPv6 流（[2409:8087:5e00:24::1e]:6060）：用户没有 IPv6 时无法播放，
 *      增加 IPv4 镜像（39.135.34.150 等）作为备选
 *    - 被封锁的境外 CDN（akamaized、cloudfront 等）：通过国内代理前缀绕过
 *    - HTTPS 流被 SNI 阻断时，尝试 HTTP 降级
 *    - 多 URL 合并：调用方传入 backupUrls 时一起返回
 *
 * 中国移动网络对 GitHub 域名存在多重封锁：
 * 1. DNS 污染 - 返回错误 IP（cdn.jsdelivr.net 自2022年起被污染）
 * 2. IP 层封锁 - 即使 DNS 正确，TCP 连接也被防火墙阻断
 * 3. SNI 封锁 - TLS 握手中的 SNI 字段被检测后连接被重置
 *
 * 镜像优先级策略：
 * - 优先使用国内可直连的 CDN 镜像（gcore.jsdelivr.net, testingcf.jsdelivr.net）
 * - 其次使用 GitHub 代理前缀（gh-proxy.com, ghproxy.net）
 * - 再次使用 GitHub 整站镜像（kkgithub.com）
 * - 最后使用清华高校镜像（长期稳定）
 * - 原始 GitHub URL 排在最后（移动网络必然失败，仅作兜底）
 */
object UrlHelper {

    private const val TAG = "UrlHelper"

    /**
     * 中国移动 IPTV IPv6 多播地址前缀
     * 这些流只在移动网络内可用，且需要 IPv6 支持
     */
    private const val CMCC_IPV6_HOST = "[2409:8087:5e00:24::1e]:6060"
    private const val CMCC_IPV6_HOST_ALT = "[2409:8087:5e00:24::1e]"
    private const val CMCC_IPV6_PATH_PREFIX = "/200000001898/4990000898000"

    /**
     * 中国移动 IPTV 的 IPv4 镜像服务器列表（按优先级）
     * 这些 IP 同样指向移动 IPTV CDN，对没有 IPv6 的用户作为备选
     *
     * 注：实际可用性取决于用户所在省份，部分省份会校验来源 IP
     */
    private val CMCC_IPV4_MIRRORS = listOf(
        "39.135.34.150:6060",     // 全国通用移动 IPTV CDN
        "39.135.34.136:6060",     // 备用
        "39.134.67.181:6060",     // 备用
        "39.134.67.10:6060",      // 备用
        "gslbserv.itv.cmcc.cn"    // 移动 IPTV 域名（DNS 解析到最近节点）
    )

    /**
     * 已知被中国移动网络封锁的境外 CDN 域名
     * 这些域名即使 DNS 正确也无法直接连接
     */
    private val BLOCKED_STREAM_DOMAINS = listOf(
        "akamaized.net",
        "akamaihd.net",
        "cloudfront.net",
        "fastly.net",
        "llnwi.net",
        "edgecastcdn.net",
        "azureedge.net",
        "cdn77.org"
    )

    /**
     * 判断是否为被中国移动网络封锁的域名（M3U 播放列表 URL）
     * 这些域名即使 DNS 解析正确也无法连接
     */
    fun isBlockedDomain(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("raw.githubusercontent.com") ||
               lower.contains("github.io") ||
               lower.contains("gist.github.com") ||
               lower.contains("objects.githubusercontent.com") ||
               lower.contains("cdn.jsdelivr.net")  // cdn.jsdelivr.net 自2022年起被DNS污染
    }

    /**
     * 判断 URL 是否为中国移动 IPTV IPv6 流
     */
    fun isCmccIpv6Stream(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("2409:8087") ||
               lower.contains(CMCC_IPV6_HOST.lowercase()) ||
               lower.contains(CMCC_IPV6_HOST_ALT.lowercase())
    }

    /**
     * 判断 URL 是否使用了被封锁的境外 CDN 域名
     */
    fun isBlockedStreamDomain(url: String): Boolean {
        val lower = url.lowercase()
        return BLOCKED_STREAM_DOMAINS.any { lower.contains(it) }
    }

    /**
     * 为给定 URL 生成备选 URL 列表（M3U 播放列表 URL 用）
     *
     * 对于被封锁的域名：镜像 URL 排在前面，原始 URL 排在最后
     * 对于普通域名：只有原始 URL（SafeDns 已能解决 DNS 问题）
     *
     * @param originalUrl 原始 URL
     * @return 按优先级排列的 URL 列表
     */
    fun getAlternativeUrls(originalUrl: String): List<String> {
        if (!isBlockedDomain(originalUrl)) {
            return listOf(originalUrl)
        }

        val urls = mutableListOf<String>()

        // ==================== raw.githubusercontent.com 镜像 ====================
        if (originalUrl.contains("raw.githubusercontent.com")) {
            // 提取 user/repo/branch/path
            // URL 格式: https://raw.githubusercontent.com/user/repo/branch/path...
            val path = originalUrl.substringAfter("raw.githubusercontent.com")
            val parts = path.split("/").filter { it.isNotEmpty() }

            if (parts.size >= 4) {
                val user = parts[0]
                val repo = parts[1]
                val branch = parts[2]
                val filePath = parts.drop(3).joinToString("/")

                // 1. gcore.jsdelivr.net - Gcore CDN，国内可用性最高
                urls.add("https://gcore.jsdelivr.net/gh/$user/$repo@$branch/$filePath")

                // 2. testingcf.jsdelivr.net - Cloudflare CDN，国内可用性高
                urls.add("https://testingcf.jsdelivr.net/gh/$user/$repo@$branch/$filePath")

                // 3. jsd.cdn.zzko.cn - 国内CDN镜像，速度快
                urls.add("https://jsd.cdn.zzko.cn/gh/$user/$repo@$branch/$filePath")

                // 4. raw.gitmirror.com - gitmirror 直接域名替换
                urls.add("https://raw.gitmirror.com/$user/$repo/$branch/$filePath")

                // 5. kkgithub.com - GitHub 整站镜像
                urls.add("https://raw.kkgithub.com/$user/$repo/$branch/$filePath")

                // 6. 清华大学 GitHub RAW 镜像（长期稳定）
                urls.add("https://mirrors.tuna.tsinghua.edu.cn/github-raw/$user/$repo/$branch/$filePath")

                // 7. gh-proxy.com - 前缀代理
                urls.add("https://gh-proxy.com/$originalUrl")

                // 8. ghproxy.net - 前缀代理
                urls.add("https://ghproxy.net/$originalUrl")

                // 9. raw.staticdn.net - raw 替换
                urls.add("https://raw.staticdn.net/$user/$repo/$branch/$filePath")
            } else {
                // 路径格式不标准，使用通用代理
                urls.add("https://raw.gitmirror.com$path")
                urls.add("https://gh-proxy.com/$originalUrl")
                urls.add("https://ghproxy.net/$originalUrl")
            }
        }

        // ==================== cdn.jsdelivr.net 镜像（已被DNS污染） ====================
        if (originalUrl.contains("cdn.jsdelivr.net")) {
            // 直接替换为可用域名
            val jsdelivrPath = originalUrl.substringAfter("cdn.jsdelivr.net")
            urls.add("https://gcore.jsdelivr.net$jsdelivrPath")
            urls.add("https://testingcf.jsdelivr.net$jsdelivrPath")
            urls.add("https://jsd.cdn.zzko.cn$jsdelivrPath")
            urls.add("https://fastly.jsdelivr.net$jsdelivrPath")
            urls.add("https://quantil.jsdelivr.net$jsdelivrPath")
        }

        // ==================== github.io 镜像 ====================
        // iptv-org.github.io/iptv/countries/cn.m3u 是 GitHub Pages 动态生成（仓库中实际文件路径是 streams/cn.m3u）
        // 实测：
        //   ✓ gcore.jsdelivr.net/gh/iptv-org/iptv@master/streams/cn.m3u     → 200
        //   ✗ gcore.jsdelivr.net/gh/iptv-org/iptv@master/countries/cn.m3u   → 404（仓库无此文件）
        //   ✗ gh-proxy.com / ghproxy.net 对 github.io 返回 403（仅代理 github.com / raw.githubusercontent.com）
        // 所以 github.io 镜像必须改写为 streams/ 路径，且不要使用 gh-proxy 类代理
        if (originalUrl.contains("github.io")) {
            if (originalUrl.contains("iptv-org.github.io/iptv/")) {
                // GitHub Pages 的 countries/cn.m3u 对应仓库 streams/cn.m3u（同样内容）
                // 注意：/guides/cn.xml 等其他路径在仓库中也是 /guides/cn.xml，结构一致
                val subPath = originalUrl.substringAfter("iptv-org.github.io/iptv/")
                val repoPath = when {
                    subPath.startsWith("countries/") -> "streams/" + subPath.substringAfter("countries/")
                    else -> subPath
                }

                // 1. gcore.jsdelivr.net - Gcore CDN（国内延迟最低，实测 ~100ms）
                urls.add("https://gcore.jsdelivr.net/gh/iptv-org/iptv@master/$repoPath")

                // 2. testingcf.jsdelivr.net - Cloudflare CDN（国内备用，~200ms）
                urls.add("https://testingcf.jsdelivr.net/gh/iptv-org/iptv@master/$repoPath")

                // 3. jsd.cdn.zzko.cn - 国内 CDN 镜像
                urls.add("https://jsd.cdn.zzko.cn/gh/iptv-org/iptv@master/$repoPath")

                // 4. fastly.jsdelivr.net - Fastly CDN
                urls.add("https://fastly.jsdelivr.net/gh/iptv-org/iptv@master/$repoPath")

                // 5. raw.gitmirror.com - gitmirror 直接 raw 域名（仓库路径直通）
                urls.add("https://raw.gitmirror.com/iptv-org/iptv/master/$repoPath")

                // 6. kkgithub.com - GitHub 整站镜像（raw 路径）
                urls.add("https://raw.kkgithub.com/iptv-org/iptv/master/$repoPath")

                // 7. 清华大学 GitHub raw 镜像
                urls.add("https://mirrors.tuna.tsinghua.edu.cn/github-raw/iptv-org/iptv/master/$repoPath")

                // 8. raw.staticdn.net
                urls.add("https://raw.staticdn.net/iptv-org/iptv/master/$repoPath")
            }

            // 通用 github.io 代理（仅对非 iptv-org 的 github.io 站点）
            // 注意：gh-proxy 类只代理 github.com / raw.githubusercontent.com，
            // 对 github.io 不支持，所以这里不再为 iptv-org 加 gh-proxy（实测 403）
        }

        // ==================== 原始 URL 放最后（兜底） ====================
        // 在移动网络上一定会失败，但保留给联通等其他网络
        urls.add(originalUrl)

        Log.d(TAG, "Generated ${urls.size} alternative URLs for: $originalUrl")
        return urls.distinct()
    }

    /**
     * 为流媒体播放 URL 生成备选 URL 列表（关键修复 + ISP 感知）
     *
     * 这是针对直播源被中国移动网络屏蔽的核心解决方案：
     *
     * 1. 中国移动 IPTV IPv6 流 → 增加 IPv4 镜像（无 IPv6 用户可用）
     * 2. 被封锁的境外 CDN → 通过国内代理前缀绕过
     * 3. HTTPS 流 → 尝试 HTTP 降级（移动网络对部分 HTTPS 流做 SNI 阻断）
     * 4. 调用方提供的 backupUrls → 一并加入候选列表
     * 5. **ISP 感知**（参考 APK 的运营商标签策略）：
     *    - 中国移动：最激进的反屏蔽（更多代理/镜像/IPv6）
     *    - 中国电信/联通：中等策略（境外 CDN 可能可直连）
     *    - 未知：按移动策略处理（确保可用性）
     *
     * @param originalUrl 频道原始流 URL
     * @param backupUrls M3U 解析出的备用 URL 列表（可为空）
     * @return 按优先级排列的 URL 列表，原始 URL 始终包含
     */
    fun getStreamAlternativeUrls(originalUrl: String, backupUrls: List<String> = emptyList()): List<String> {
        val urls = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val isCMCC = ISPDetector.isCMCCOrUnknown()

        fun addCandidate(url: String) {
            if (url.isNotBlank() && seen.add(url.lowercase())) {
                urls.add(url)
            }
        }

        // ==================== 中国移动 IPTV IPv6 流 → 增加 IPv4 镜像 ====================
        // 注意：IPv4 镜像（39.135.34.150 等）只对应 [2409:8087:5e00:24::1e]:6060 这一个 IPv6 主机
        // 其他 IPv6 主机（如 [2409:8087:3869:8021:1001::e5]:6610、[2409:8087:1a01:df::4077]:80）
        // 走的是不同的 CDN 节点，不能简单替换为 IPv4 - 这种情况保留原始 IPv6 URL，让播放器直接尝试
        if (isCmccIpv6Stream(originalUrl)) {
            val isLegacyHost = originalUrl.contains("[2409:8087:5e00:24::1e]:6060", ignoreCase = true) ||
                    originalUrl.contains("[2409:8087:5e00:24::1e]/", ignoreCase = true) ||
                    originalUrl.contains("[2409:8087:5e00:24::1f]", ignoreCase = true)

            if (isLegacyHost) {
                Log.d(TAG, "Detected legacy CMCC IPTV host, adding IPv4 mirrors: $originalUrl")

                // 提取 IPv6 后的路径部分
                val pathStart = originalUrl.indexOf("]", ignoreCase = true)
                if (pathStart > 0) {
                    val afterBracket = originalUrl.substring(pathStart + 1)
                    val pathPart = afterBracket.substringAfter("/", "/")
                    val streamPath = if (pathPart.startsWith("/")) pathPart else "/$pathPart"

                    // 1. 各 IPv4 镜像（保持原始路径）
                    for (mirror in CMCC_IPV4_MIRRORS) {
                        addCandidate("http://$mirror$streamPath")
                    }

                    // 2. 域名形式（DNS 解析到最近节点，更稳定）
                    if (streamPath.startsWith(CMCC_IPV6_PATH_PREFIX)) {
                        val tailPath = streamPath.substringAfter(CMCC_IPV6_PATH_PREFIX)
                        addCandidate("http://gslbserv.itv.cmcc.cn$CMCC_IPV6_PATH_PREFIX$tailPath")
                    }
                }
            } else {
                // 其他 CMCC IPv6 主机 - 保留原始 IPv6 URL，不生成无效的 IPv4 镜像
                Log.d(TAG, "CMCC IPv6 stream with non-legacy host, keeping original: $originalUrl")
            }
        }

        // ==================== 被封锁的境外 CDN → 代理前缀 ====================
        if (isBlockedStreamDomain(originalUrl)) {
            Log.d(TAG, "Detected blocked CDN stream, adding proxy: $originalUrl")
            // 通过国内反代访问
            addCandidate("https://gh-proxy.com/$originalUrl")
            addCandidate("https://ghproxy.net/$originalUrl")
            addCandidate("https://corsproxy.io/?url=$originalUrl")
            // ISP 感知：移动网络下增加更多代理选项（参考 APK 的多源策略）
            if (isCMCC) {
                addCandidate("https://ghproxy.cc/$originalUrl")
                addCandidate("https://mirror.ghproxy.com/$originalUrl")
            }
        }

        // ==================== 已知被移动网络屏蔽的境外 IP → 替换为国内镜像 ====================
        // 参考 APK 的 ISP 标签过滤策略：移动网络下不使用境外 IP 的流
        // iptv-org 的 cn.m3u 中大量频道使用北美服务器（69.x, 74.91.x, 198.204.x 等），
        // 这些在移动网络下必定被屏蔽，需要替换为国内可访问的替代源
        if (isCMCC) {
            val blockedIpAlt = getAlternativeForBlockedIp(originalUrl)
            if (blockedIpAlt != null) {
                Log.d(TAG, "CMCC network: replacing blocked IP URL with alternative: $blockedIpAlt")
                addCandidate(blockedIpAlt)
            }
        }

        // ==================== HTTPS 流 → HTTP 降级（仅当不是 GitHub 等强制 HTTPS 域名） ====================
        if (originalUrl.startsWith("https://", ignoreCase = true) &&
            !isBlockedDomain(originalUrl) &&
            !isBlockedStreamDomain(originalUrl)) {
            // 部分移动网络对特定 HTTPS 流做 SNI 阻断，HTTP 可绕过
            // ISP 感知：移动网络下始终尝试 HTTP 降级；电信/联通下仅对 CDN 域名尝试
            if (isCMCC || isBlockedStreamDomain(originalUrl)) {
                val httpVersion = "http://" + originalUrl.substring(8)
                addCandidate(httpVersion)
            }
        }

        // ==================== M3U 提供的备用 URL ====================
        for (backup in backupUrls) {
            // 递归处理每个备用 URL（可能也是 IPv6 或 CDN）
            if (backup != originalUrl) {
                addCandidate(backup)
                // 同时为备用 URL 也生成镜像
                if (isCmccIpv6Stream(backup) || isBlockedStreamDomain(backup)) {
                    getStreamAlternativeUrls(backup, emptyList()).forEach { addCandidate(it) }
                }
            }
        }

        // ==================== ISP 感知：URL 重新排序 ====================
        // 中国移动网络下：CMCC IPv6 URL > 国内域名 > 其他 > 已知屏蔽境外 IP
        // 电信/联通下：原始 URL 优先（封锁较轻）
        if (isCMCC && urls.size > 1) {
            val sorted = urls.sortedWith(compareBy { ispAwareUrlRank(it) })
            urls.clear()
            seen.clear()
            sorted.forEach { addCandidate(it) }
        }

        // ==================== 原始 URL 始终包含（作为兜底） ====================
        addCandidate(originalUrl)

        Log.d(TAG, "Generated ${urls.size} stream alternatives for: $originalUrl (ISP: ${ISPDetector.currentISP.label})")
        return urls
    }

    /**
     * ISP 感知的 URL 可达性排序
     *
     * 参考 APK 的 Channel.java URL 过滤策略：
     * - 中国移动网络下，已知被屏蔽的境外 IP 优先级最低
     * - 移动 IPTV IPv6 地址优先级最高（移动网络内必达）
     *
     * @return 排序值，越小优先级越高
     */
    private fun ispAwareUrlRank(url: String): Int {
        val lower = url.lowercase()
        return when {
            // 咪咕CDN（miguvideo.com）→ 中国移动自有CDN，最高优先级
            // 从APK反编译分析：咪咕CDN在移动网络下天然不被屏蔽
            lower.contains("miguvideo.com") -> 0
            // 中国移动 IPTV IPv6 - 极高优先级
            lower.contains("2409:8087") -> 0
            // 移动 IPTV IPv4 镜像
            lower.contains("39.135.") || lower.contains("39.134.") ||
            lower.contains("gslbserv.itv.cmcc.cn") -> 1
            // 腾讯视频CDN（video.qq.com）→ 移动网络下通常可用
            lower.contains("video.qq.com") || lower.contains("tcloud") -> 2
            // 国内域名
            lower.contains(".cn/") || lower.contains(".cn:") || lower.endsWith(".cn") ||
            lower.contains("chinamobile.com") || lower.contains("cctv.com") ||
            lower.contains("cctv.cn") || lower.contains("cntv.cn") ||
            lower.contains("pdtvhd.com") || lower.contains("douyincdn.com") -> 2
            // 代理/镜像 URL（通过代理访问境外内容）
            lower.contains("gh-proxy.com") || lower.contains("ghproxy.net") ||
            lower.contains("ghproxy.cc") || lower.contains("corsproxy.io") ||
            lower.contains("gitmirror.com") || lower.contains("kkgithub.com") -> 3
            // 国内 IP 段
            lower.startsWith("http://39.") || lower.startsWith("http://112.") ||
            lower.startsWith("http://117.") || lower.startsWith("http://118.") ||
            lower.startsWith("http://121.") || lower.startsWith("http://122.") ||
            lower.startsWith("http://123.") || lower.startsWith("http://183.") ||
            lower.startsWith("http://222.") || lower.startsWith("http://223.") -> 4
            // 已知被屏蔽的境外 IP - 最低优先级
            lower.startsWith("http://69.") || lower.startsWith("http://74.91.") ||
            lower.startsWith("http://198.204.") || lower.startsWith("http://192.151.") ||
            lower.startsWith("http://23.") || lower.startsWith("http://45.") ||
            lower.startsWith("http://104.") || lower.startsWith("http://162.") -> 7
            // 其他未分类
            else -> 5
        }
    }

    /**
     * 为已知被中国移动网络屏蔽的境外 IP URL 生成替代 URL
     *
     * iptv-org 的 cn.m3u 中许多 CCTV/卫视频道使用北美服务器（如 69.30.245.50、74.91.26.218:82），
     * 这些在中国移动网络下 100% 被屏蔽。
     *
     * 参考电视直播应用的策略：服务端为不同 ISP 提供不同 CDN URL。
     * 我们无法控制服务端，但可以尝试将境外 IP 替换为已知的国内替代 CDN。
     *
     * 当前实现：返回 null（暂无替代源映射表）。
     * 未来可扩展：从 fallback_channels.m3u 中提取同频道名的国内 URL 作为替代。
     */
    private fun getAlternativeForBlockedIp(url: String): String? {
        // 当前暂不实现替代 IP 映射，因为：
        // 1. 需要维护一个完整的境外IP→国内IP映射表
        // 2. M3UParser 的同名频道合并 + URL 排序已经能处理大部分情况
        // 3. fallback_channels.m3u 中的国内 IPv6 URL 已经作为备选
        return null
    }
}
