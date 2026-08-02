package com.tvlive.app.player

import android.util.Xml
import com.tvlive.app.data.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EPG (电子节目指南) XMLTV 解析器
 *
 * 解析 XMLTV 格式的节目单 XML，格式示例：
 * <tv>
 *   <programme start="20240101120000 +0800" stop="20240101130000 +0800" channel="cctv-1">
 *     <title>新闻联播</title>
 *     <desc>今日新闻</desc>
 *   </programme>
 * </tv>
 */
object EpgParser {

    private val DATE_FORMAT = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())

    /**
     * 解析 EPG XML，返回 channelId -> 节目列表
     */
    fun parse(xml: String): Map<String, List<EpgProgram>> {
        val result = mutableMapOf<String, MutableList<EpgProgram>>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var currentChannel = ""
        var currentTitle = ""
        var currentStart = 0L
        var currentStop = 0L
        var currentDesc: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            currentChannel = parser.getAttributeValue(null, "channel") ?: ""
                            val startStr = parser.getAttributeValue(null, "start")
                            val stopStr = parser.getAttributeValue(null, "stop")
                            currentStart = parseDate(startStr)
                            currentStop = parseDate(stopStr)
                        }
                        "title" -> currentTitle = parser.nextText()
                        "desc" -> currentDesc = parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "programme" && currentChannel.isNotBlank()) {
                        val program = EpgProgram(
                            title = currentTitle,
                            start = currentStart,
                            stop = currentStop,
                            desc = currentDesc
                        )
                        result.getOrPut(currentChannel) { mutableListOf() }.add(program)
                    }
                }
            }
            eventType = parser.next()
        }

        return result
    }

    private fun parseDate(str: String?): Long {
        if (str.isNullOrBlank()) return 0L
        return try {
            DATE_FORMAT.timeZone = TimeZone.getDefault()
            DATE_FORMAT.parse(str)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取当前正在播放的节目
     */
    fun getCurrentProgram(programs: List<EpgProgram>?): EpgProgram? {
        if (programs.isNullOrEmpty()) return null
        val now = System.currentTimeMillis()
        return programs.find { now in it.start..it.stop }
    }

    /**
     * 获取接下来播放的节目
     */
    fun getNextProgram(programs: List<EpgProgram>?): EpgProgram? {
        if (programs.isNullOrEmpty()) return null
        val now = System.currentTimeMillis()
        return programs.firstOrNull { it.start > now }
    }

    fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return "--:--"
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
