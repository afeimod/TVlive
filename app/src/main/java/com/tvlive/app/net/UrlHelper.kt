package com.tvlive.app.net

import android.util.Log

/**
 * URL 镜像工具
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
     * 判断是否为被中国移动网络封锁的域名
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
     * 为给定 URL 生成备选 URL 列表
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
}
