package com.tvlive.app.data

/**
 * 默认内置直播源
 *
 * 只使用 assets 本地源, 不依赖在线刷新
 * 本地源文件: assets/china_channels.m3u (央视+卫视, 国内直连IP)
 */
object DefaultSources {

    /** 本地内置源 (assets:// 协议) */
    const val LOCAL_SOURCE_URL = "assets://china_channels.m3u"
    const val LOCAL_SOURCE_NAME = "内置中国频道源"
}
