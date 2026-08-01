package com.tvlive.app.data

import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.Source

/**
 * 默认内置直播源 - 均为公开免费源
 */
object DefaultSources {

    val sources = listOf(
        Source(
            name = "iptv-org 中国频道",
            url = "https://iptv-org.github.io/iptv/countries/cn.m3u",
            isDefault = true
        ),
        Source(
            name = "iptv-org 全球频道",
            url = "https://iptv-org.github.io/iptv/index.m3u"
        ),
        Source(
            name = "zbds 每日更新源",
            url = "https://live.zbds.top/tv/iptv4.m3u"
        ),
        Source(
            name = "joevess 央视卫视源",
            url = "https://raw.githubusercontent.com/joevess/IPTV/main/home.m3u8"
        ),
        Source(
            name = "yuanzl77 国内直播源",
            url = "https://raw.githubusercontent.com/yuanzl77/IPTV/main/live.m3u"
        ),
        Source(
            name = "Free-TV 全球免费",
            url = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
        ),
        Source(
            name = "Collect-IPTV 精选合集",
            url = "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/refs/heads/main/best_sorted.m3u"
        )
    )

    /** EPG 节目单地址 */
    const val EPG_URL = "https://iptv-org.github.io/epg/guides/cn.xml"
}
