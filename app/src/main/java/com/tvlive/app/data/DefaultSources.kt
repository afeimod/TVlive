package com.tvlive.app.data

import com.tvlive.app.data.model.Source

/**
 * 默认内置直播源 - 均为公开免费源
 *
 * 优先使用国内可直连的源，避免电视盒子无法访问 GitHub
 * 默认只启用中国频道源，其余源预置但默认禁用
 */
object DefaultSources {

    val sources = listOf(
        // === 国内可直连源 (默认启用) ===
        Source(
            name = "zbds IPTV4 (国内直连)",
            url = "https://live.zbds.top/tv/iptv4.m3u",
            isDefault = true,
            enabled = true
        ),
        Source(
            name = "kilvn 自动更新源 (国内直连)",
            url = "https://live.kilvn.com/iptv.m3u",
            enabled = true
        ),
        Source(
            name = "范明明国内镜像 (IPv6)",
            url = "https://live.fanmingming.cn/tv/m3u/ipv6.m3u",
            enabled = false
        ),

        // === bgithub 镜像源 (GitHub 加速, 国内可访问) ===
        Source(
            name = "Guovin IPTV结果源",
            url = "https://raw.bgithub.xyz/Guovin/iptv-api/gd/output/result.m3u",
            enabled = false
        ),
        Source(
            name = "YanG 集合源",
            url = "https://raw.bgithub.xyz/YanG-1989/m3u/main/Gather.m3u",
            enabled = false
        ),
        Source(
            name = "fanmingming 直播源",
            url = "https://raw.bgithub.xyz/fanmingming/live/main/tv/m3u/v6.m3u",
            enabled = false
        ),

        // === Gitee 源 (国内托管) ===
        Source(
            name = "Gitee 直播源合集",
            url = "https://gitee.com/xxy002/zhiboyuan/raw/master/dsy",
            enabled = false
        ),

        // === 其他源 ===
        Source(
            name = "iptv-org 中国频道",
            url = "https://iptv-org.github.io/iptv/countries/cn.m3u",
            enabled = false
        ),
        Source(
            name = "iptv-org 全球频道",
            url = "https://iptv-org.github.io/iptv/index.m3u",
            enabled = false
        )
    )

    /** EPG 节目单地址 */
    const val EPG_URL = "https://live.zbds.top/tv/epg.xml"
}
