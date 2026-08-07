package com.tvlive.app.data

import com.tvlive.app.data.model.Source

/**
 * 默认内置直播源 - 均为公开免费源
 *
 * 仅保留一个直播源：iptv-org 中国频道（github.io 版本）
 * URL: https://iptv-org.github.io/iptv/countries/cn.m3u
 *
 * 中国移动 5G 网络优化说明（v5）：
 * - github.io 在中国移动网络下被 DNS 污染 + IP 封锁
 *   UrlHelper 会自动生成多个国内可直连镜像 URL（jsdelivr 国内 CDN、gitmirror、kkgithub、清华镜像等）
 * - 加载时 ChannelRepository 会并行尝试所有镜像，第一个成功的即返回
 *   不再顺序逐个尝试，5G 网络下通常 1-2 秒内即可完成加载
 * - SafeDns 提供 DoH 并发查询 + 短缓存，进一步降低 DNS 延迟
 * - 内置备用频道（assets/fallback_channels.m3u）确保网络全失败时仍可观看
 *   每个频道同时提供 IPv6 + 多个 IPv4 中国移动 IPTV 地址，由播放器自动降级
 */
object DefaultSources {

    /** 源版本号 - 版本变化时会重新初始化默认源 */
    const val VERSION = 5

    val sources = listOf(
        // ==================== 唯一默认启用源 ====================
        // iptv-org 中国频道 - GitHub Pages 域名
        // UrlHelper 会自动生成 7+ 个国内可直连镜像（jsdelivr 国内 CDN、gitmirror、kkgithub、清华镜像等）
        // ChannelRepository 并行请求所有镜像，5G 网络下通常 1-2 秒完成加载
        Source(
            name = "iptv-org 中国频道",
            url = "https://iptv-org.github.io/iptv/countries/cn.m3u",
            isDefault = true,
            enabled = true
        )
    )

    /** EPG 节目单地址（GitHub URL，UrlHelper自动生成镜像） */
    const val EPG_URL = "https://iptv-org.github.io/iptv/guides/cn.xml"
}
