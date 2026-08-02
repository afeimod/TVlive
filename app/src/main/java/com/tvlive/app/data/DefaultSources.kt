package com.tvlive.app.data

import com.tvlive.app.data.model.Source

/**
 * 默认内置直播源 - 均为公开免费源
 *
 * 默认只启用中国频道源，其余源预置但默认禁用，用户可在源管理中手动开启
 *
 * 网络兼容性说明：
 * - 所有源已通过 SafeDns 自定义 DNS 解析绕过运营商 DNS 污染
 * - raw.githubusercontent.com 源在原始 URL 失败时会自动尝试国内镜像（gitmirror/ghp.ci/ghproxy）
 * - github.io 源通过 SafeDns 解析后通常可直接访问（Fastly CDN 在移动网络下可达）
 */
object DefaultSources {

    val sources = listOf(
        Source(
            name = "iptv-org 中国频道",
            url = "https://iptv-org.github.io/iptv/countries/cn.m3u",
            isDefault = true,
            enabled = true
        ),
        Source(
            name = "zbds 每日更新源",
            url = "https://live.zbds.top/tv/iptv4.m3u",
            enabled = false
        ),
        Source(
            name = "joevess 央视卫视源",
            url = "https://raw.githubusercontent.com/joevess/IPTV/main/home.m3u8",
            enabled = false
        ),
        Source(
            name = "yuanzl77 国内直播源",
            url = "https://raw.githubusercontent.com/yuanzl77/IPTV/main/live.m3u",
            enabled = false
        ),
        Source(
            name = "iptv-org 全球频道",
            url = "https://iptv-org.github.io/iptv/index.m3u",
            enabled = false
        ),
        Source(
            name = "Free-TV 全球免费",
            url = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
            enabled = false
        ),
        Source(
            name = "Collect-IPTV 精选合集",
            url = "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/refs/heads/main/best_sorted.m3u",
            enabled = false
        )
    )

    /** EPG 节目单地址 */
    const val EPG_URL = "https://iptv-org.github.io/epg/guides/cn.xml"
}
