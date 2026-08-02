package com.tvlive.app.net

import android.util.Log

/**
 * URL 镜像工具
 *
 * 中国移动网络对 raw.githubusercontent.com 等域名存在 IP 层封锁，
 * 即使 DNS 解析正确也可能无法连接。本工具提供国内可用的 GitHub 镜像地址，
 * 在原始 URL 连接失败时自动切换到镜像地址重试。
 *
 * 对于非 GitHub 域名，SafeDns 的自定义 DNS 解析通常已能解决问题。
 */
object UrlHelper {

    private const val TAG = "UrlHelper"

    /**
     * raw.githubusercontent.com 的国内镜像前缀列表
     * 按优先级排序：gitmirror > ghp.ci > ghproxy
     */
    private val GITHUB_RAW_MIRRORS = listOf(
        "https://raw.gitmirror.com",
        "https://ghp.ci",
        "https://mirror.ghproxy.com"
    )

    /**
     * 为给定 URL 生成备选 URL 列表（包含原始 URL + 镜像 URL）
     *
     * @param originalUrl 原始 URL
     * @return 按优先级排列的 URL 列表，第一个是原始 URL
     */
    fun getAlternativeUrls(originalUrl: String): List<String> {
        val urls = mutableListOf(originalUrl)

        // raw.githubusercontent.com 镜像
        if (originalUrl.contains("raw.githubusercontent.com")) {
            val path = originalUrl.substringAfter("raw.githubusercontent.com")
            for (mirror in GITHUB_RAW_MIRRORS) {
                if (mirror == "https://raw.gitmirror.com") {
                    // gitmirror 直接替换域名，路径不变
                    urls.add(mirror + path)
                } else {
                    // ghp.ci / ghproxy 需要在前面加上原始完整 URL
                    urls.add("$mirror/$originalUrl")
                }
            }
        }

        // github.io 页面镜像（iptv-org 等）
        if (originalUrl.contains("github.io")) {
            val ghProxy = "https://mirror.ghproxy.com"
            urls.add("$ghProxy/$originalUrl")
            urls.add("https://ghp.ci/$originalUrl")
        }

        // 去重
        return urls.distinct()
    }

    /**
     * 判断 URL 是否为可能被移动网络封锁的 GitHub 域名
     */
    fun isGithubUrl(url: String): Boolean {
        return url.contains("githubusercontent.com") ||
               url.contains("github.io") ||
               url.contains("github.com")
    }

    /**
     * 记录某个 URL 失败，在后续请求中降低其优先级
     * （简单实现：返回镜像列表中下一个可用的 URL）
     *
     * @param failedUrl 失败的 URL
     * @param allUrls 全部备选 URL 列表
     * @return 还未尝试的 URL 列表
     */
    fun getRemainingUrls(failedUrl: String, allUrls: List<String>): List<String> {
        val failedIndex = allUrls.indexOf(failedUrl)
        if (failedIndex < 0 || failedIndex >= allUrls.size - 1) return emptyList()
        Log.d(TAG, "URL failed: $failedUrl, trying next alternative...")
        return allUrls.drop(failedIndex + 1)
    }
}
