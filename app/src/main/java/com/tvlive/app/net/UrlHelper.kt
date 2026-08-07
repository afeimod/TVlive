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
        if (originalUrl.contains("github.io")) {
            // iptv-org.github.io/iptv/countries/cn.m3u
            // 对应 jsdelivr: gcore.jsdelivr.net/gh/iptv-org/iptv@master/streams/cn.m3u
            urls.add("https://gh-proxy.com/$originalUrl")
            urls.add("https://ghproxy.net/$originalUrl")

            // 尝试 jsdelivr（部分 github.io 有对应仓库）
            if (originalUrl.contains("iptv-org.github.io/iptv/")) {
                val subPath = originalUrl.substringAfter("iptv-org.github.io/iptv/")
                // iptv-org/iptv 仓库的 streams 目录有对应文件
                urls.add("https://gcore.jsdelivr.net/gh/iptv-org/iptv@master/streams/$subPath")
                urls.add("https://testingcf.jsdelivr.net/gh/iptv-org/iptv@master/streams/$subPath")
                urls.add("https://jsd.cdn.zzko.cn/gh/iptv-org/iptv@master/streams/$subPath")
            }
        }

        // ==================== 原始 URL 放最后（兜底） ====================
        // 在移动网络上一定会失败，但保留给联通等其他网络
        urls.add(originalUrl)

        Log.d(TAG, "Generated ${urls.size} alternative URLs for: $originalUrl")
        return urls.distinct()
    }

    /**
     * 为流媒体播放 URL 生成备选 URL 列表（关键修复）
     *
     * 这是针对直播源被中国移动网络屏蔽的核心解决方案：
     *
     * 1. 中国移动 IPTV IPv6 流 → 增加 IPv4 镜像（无 IPv6 用户可用）
     * 2. 被封锁的境外 CDN → 通过国内代理前缀绕过
     * 3. HTTPS 流 → 尝试 HTTP 降级（移动网络对部分 HTTPS 流做 SNI 阻断）
     * 4. 调用方提供的 backupUrls → 一并加入候选列表
     *
     * @param originalUrl 频道原始流 URL
     * @param backupUrls M3U 解析出的备用 URL 列表（可为空）
     * @return 按优先级排列的 URL 列表，原始 URL 始终包含
     */
    fun getStreamAlternativeUrls(originalUrl: String, backupUrls: List<String> = emptyList()): List<String> {
        val urls = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun addCandidate(url: String) {
            if (url.isNotBlank() && seen.add(url.lowercase())) {
                urls.add(url)
            }
        }

        // ==================== 中国移动 IPTV IPv6 流 → 增加 IPv4 镜像 ====================
        if (isCmccIpv6Stream(originalUrl)) {
            Log.d(TAG, "Detected CMCC IPv6 stream, adding IPv4 mirrors: $originalUrl")

            // 提取 IPv6 后的路径部分
            // 形如: http://[2409:8087:5e00:24::1e]:6060/200000001898/4990000898000/1.m3u8
            val pathStart = originalUrl.indexOf("]", ignoreCase = true)
            if (pathStart > 0) {
                val afterBracket = originalUrl.substring(pathStart + 1)
                // 跳过 ":port" 部分，取路径
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
        }

        // ==================== 被封锁的境外 CDN → 代理前缀 ====================
        if (isBlockedStreamDomain(originalUrl)) {
            Log.d(TAG, "Detected blocked CDN stream, adding proxy: $originalUrl")
            // 通过国内反代访问
            addCandidate("https://gh-proxy.com/$originalUrl")
            addCandidate("https://ghproxy.net/$originalUrl")
            addCandidate("https://corsproxy.io/?url=$originalUrl")
        }

        // ==================== HTTPS 流 → HTTP 降级（仅当不是 GitHub 等强制 HTTPS 域名） ====================
        if (originalUrl.startsWith("https://", ignoreCase = true) &&
            !isBlockedDomain(originalUrl) &&
            !isBlockedStreamDomain(originalUrl)) {
            // 部分移动网络对特定 HTTPS 流做 SNI 阻断，HTTP 可绕过
            val httpVersion = "http://" + originalUrl.substring(8)
            addCandidate(httpVersion)
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

        // ==================== 原始 URL 始终包含（作为兜底） ====================
        addCandidate(originalUrl)

        Log.d(TAG, "Generated ${urls.size} stream alternatives for: $originalUrl")
        return urls
    }
}
