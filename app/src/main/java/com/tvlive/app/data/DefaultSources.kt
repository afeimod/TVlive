package com.tvlive.app.data

/**
 * 默认内置直播源
 *
 * 使用 GitHub 代理地址访问 iptv-org 中国频道源, 避免直接访问 GitHub
 * 同时保留 assets 本地源作为离线备用
 */
object DefaultSources {

    /** 本地内置源 (assets:// 协议) - 离线备用 */
    const val LOCAL_SOURCE_URL = "assets://china_channels.m3u"
    const val LOCAL_SOURCE_NAME = "内置中国频道源(离线)"

    /**
     * iptv-org 中国频道在线源 (通过 GitHub 代理访问)
     * 代理1: gh-proxy.com
     * 代理2: ghfast.top (备用)
     */
    const val ONLINE_SOURCE_NAME = "iptv-org中国频道(代理)"
    const val ONLINE_SOURCE_URL_1 = "https://gh-proxy.com/https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cn.m3u"
    const val ONLINE_SOURCE_URL_2 = "https://ghfast.top/https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cn.m3u"
}
