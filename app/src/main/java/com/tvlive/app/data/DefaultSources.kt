package com.tvlive.app.data

import com.tvlive.app.data.model.Source

/**
 * 默认内置直播源 - 均为公开免费源
 *
 * 默认只启用国内可直连的源，其余源预置但默认禁用
 *
 * 网络兼容性说明：
 * - GitHub 源使用原始 raw.githubusercontent.com URL
 *   UrlHelper 会自动生成 9+ 个镜像备选 URL（gcore/testingcf/jsd.cdn.zzko.cn 等）
 *   镜像 URL 排在前面，原始 URL 排在最后作为兜底
 * - cdn.jsdelivr.net 自2022年起被DNS污染，不可直接使用
 * - SafeDns 提供四层 DNS 解析：系统DNS → 公共DNS(UDP) → DoH(HTTPS) → 硬编码IP
 * - 内置备用频道（assets/fallback_channels.m3u）确保网络全失败时仍可观看
 * - 移动/电信/联通网络均可正常访问
 */
object DefaultSources {

    /** 源版本号 - 版本变化时会重新初始化默认源 */
    const val VERSION = 3

    val sources = listOf(
        // ==================== 默认启用的源 ====================

        // 1. iptv-org 中国频道 - GitHub源，UrlHelper自动生成镜像
        Source(
            name = "iptv-org 中国频道",
            url = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cn.m3u",
            isDefault = true,
            enabled = true
        ),

        // 2. zbds 每日更新源 - 国内直连，不依赖GitHub
        Source(
            name = "zbds 每日更新源",
            url = "https://live.zbds.top/tv/iptv4.m3u",
            enabled = true
        ),

        // 3. fanmingming 直播源 - 国内直连
        Source(
            name = "fanmingming 直播源",
            url = "https://live.fanmingming.com/tv/m3u/iPV6.m3u",
            enabled = true
        ),

        // ==================== 预置但默认禁用的源 ====================

        // joevess 央视卫视源 - GitHub源，UrlHelper自动生成镜像
        Source(
            name = "joevess 央视卫视源",
            url = "https://raw.githubusercontent.com/joevess/IPTV/main/home.m3u8",
            enabled = false
        ),

        // yuanzl77 国内直播源 - GitHub源，UrlHelper自动生成镜像
        Source(
            name = "yuanzl77 国内直播源",
            url = "https://raw.githubusercontent.com/yuanzl77/IPTV/main/live.m3u",
            enabled = false
        ),

        // iptv-org 全球频道 - GitHub源，UrlHelper自动生成镜像
        Source(
            name = "iptv-org 全球频道",
            url = "https://raw.githubusercontent.com/iptv-org/iptv/master/index.m3u",
            enabled = false
        ),

        // Free-TV 全球免费 - GitHub源，UrlHelper自动生成镜像
        Source(
            name = "Free-TV 全球免费",
            url = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
            enabled = false
        ),

        // Collect-IPTV 精选合集 - GitHub源，UrlHelper自动生成镜像
        Source(
            name = "Collect-IPTV 精选合集",
            url = "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/refs/heads/main/best_sorted.m3u",
            enabled = false
        )
    )

    /** EPG 节目单地址（GitHub URL，UrlHelper自动生成镜像） */
    const val EPG_URL = "https://raw.githubusercontent.com/iptv-org/epg/master/sites/channels.csv"
}
