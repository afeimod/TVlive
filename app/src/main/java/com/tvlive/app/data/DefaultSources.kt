package com.tvlive.app.data

/**
 * 默认内置直播源
 *
 * 使用国内直连源 (zbds.top), 不依赖 GitHub
 * 同时保留 assets 本地源作为离线备用
 */
object DefaultSources {

    /** 本地内置源 (assets:// 协议) - 离线备用 */
    const val LOCAL_SOURCE_URL = "assets://china_channels.m3u"
    const val LOCAL_SOURCE_NAME = "内置中国频道源(离线)"

    /**
     * 国内直连在线源 (不经过 GitHub)
     * zbds.top: 国内服务器, IPv4/IPv6 双栈, 自带台标和EPG
     */
    const val ONLINE_SOURCE_NAME = "国内直播源(zbds)"
    const val ONLINE_SOURCE_URL = "https://live.zbds.top/tv/iptv4.m3u"
}
