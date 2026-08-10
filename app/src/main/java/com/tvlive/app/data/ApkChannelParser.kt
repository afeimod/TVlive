package com.tvlive.app.data

import android.util.Log
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.Source
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * APK 加密频道数据解析器（v3 - 完全按APK运行时逻辑）
 *
 * 核心逻辑（与APK完全一致）：
 *
 * 1. gzip解压 → JSON解析 → 获取Channels对象
 * 2. 对每个Channel的urls数组，逐个AES解密
 * 3. 解密后检查ISP标签（$Y/$D/$L）和IPv6标签（$i6）
 *    - 有$标签 → 只保留匹配当前ISP的URL（APK的Channel.setUrls逻辑）
 *    - $i6 → 只在IPv6可用时保留
 *    - 无$标签 → 所有ISP通用，始终保留
 * 4. 保留URL的原始协议前缀（sys_http://, ikkHeaders://等）
 *    不在这里解析，而是在播放时由Channel.resolveForPlayback()解析
 *    这与APK一致——APK也是存原始加密URL，播放时才解密+协议解析
 * 5. URL按CDN优先级排序（移动网络：咪咕 > $Y > sys_ > 腾讯 > 抖音）
 *
 * 密钥来源：jadx反编译 → libjerry.so JNI → getJniString() → "you!je@19rr$20y#"
 */
object ApkChannelParser {

    private const val TAG = "ApkChannelParser"

    /**
     * AES解密单个URL，并按ISP过滤（完全按APK的Channel.setUrls()逻辑）
     *
     * @param encryptedBase64 AES加密+Base64编码的URL
     * @param currentIspTag 当前ISP标签："Y"=移动, "D"=电信, "L"=联通, null=未知
     * @param ipv6Supported 设备是否支持IPv6（对应APK的App.g）
     * @return 解密后的URL（保留协议前缀），如果ISP不匹配则返回null
     */
    private fun decryptAndFilter(
        encryptedBase64: String,
        currentIspTag: String?,
        ipv6Supported: Boolean
    ): String? {
        return try {
            // AES-128-CBC 解密
            val raw = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
            val keySpec = SecretKeySpec(DefaultSources.AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(DefaultSources.AES_IV_BYTES)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(raw)
            var url = String(decrypted, Charsets.UTF_8)

            // ========== 1. 处理 $i6 后缀（IPv6专用URL）==========
            // 按APK逻辑：只有App.g=true时才保留$i6 URL
            if (url.endsWith("\$i6")) {
                if (!ipv6Supported) {
                    return null  // 不支持IPv6，丢弃此URL
                }
                url = url.dropLast(3)  // 去掉$i6后缀
            }

            // ========== 2. 处理 $ISP 标签（按APK的Channel.setUrls逻辑）==========
            // $Y=移动专用CDN, $D=电信专用CDN, $L=联通专用CDN
            if (url.contains("\$")) {
                val parts = url.split("\$", limit = 2)
                val actualUrl = parts[0]
                val ispTag = parts.getOrNull(1) ?: ""

                if (ispTag.isNotEmpty()) {
                    // 按APK逻辑：只有匹配当前ISP才保留
                    // TextUtils.isEmpty(App.f) → ISP未知，保留所有
                    // substring.toUpperCase().contains(App.f.toUpperCase()) → 标签匹配
                    val isMatch = currentIspTag == null ||  // ISP未知，保留所有
                                  ispTag.uppercase().contains(currentIspTag.uppercase())

                    if (!isMatch) {
                        return null  // 不匹配当前ISP，丢弃（APK中直接不add到urls列表）
                    }

                    // 匹配：只保留$前面的实际URL部分
                    url = actualUrl
                }
            }

            // ========== 3. 保留协议前缀（播放时再解析）==========
            // 这是关键：APK也是存原始URL，播放时才在IjkVideoView.setVideoPath()中解析
            // sys_http:// → 播放时去掉sys_，用系统播放器
            // ikkHeaders:// → 播放时解析?url=&Referer=，注入Headers
            // http(s):// → 直接播放
            // 其他协议（migutv, ccto, gwpd等）→ 无法播放，过滤掉

            val resolvedUrl: String? = when {
                // 可直接转换的协议 → 保留前缀
                url.startsWith("sys_http://") -> url
                url.startsWith("sys_https://") -> url
                url.startsWith("sys_yscj://") -> url  // 播放时去掉sys_前缀
                url.startsWith("ikkHeaders://") -> url
                url.startsWith("kooHeaders://") -> url
                url.startsWith("Headers://") -> url
                url.startsWith("ikk://") -> url
                url.startsWith("koo://") -> url

                // yscj:// 可能包含嵌入的HTTP URL
                url.startsWith("yscj://") || url.startsWith("ysnew://") -> {
                    val inner = url.removePrefix("yscj://").removePrefix("ysnew://")
                    if (inner.startsWith("http://") || inner.startsWith("https://")) inner else null
                }

                // 直接HTTP/HTTPS URL
                url.startsWith("http://") || url.startsWith("https://") -> url

                // 无法播放的协议 → 过滤
                url.startsWith("migutv") || url.startsWith("miguytv") ||
                url.startsWith("miguak") || url.startsWith("dytv") ||
                url.startsWith("gwpd") || url.startsWith("ccto") ||
                url.startsWith("iqlorg") || url.startsWith("sccd") ||
                url.startsWith("goodtv") || url.startsWith("touch") ||
                url.startsWith("bestv") || url.startsWith("hentv") -> null

                // 其他未知协议 → 尝试保留（可能MainParser能解析）
                else -> null
            }

            resolvedUrl
        } catch (e: Exception) {
            Log.w(TAG, "Decrypt failed: ${e.message}")
            null
        }
    }

    /**
     * CDN优先级评分（为移动网络优化）
     * 按APK实际行为：咪咕CDN最优先（移动自有），然后sys_（绕DNS），然后其他
     */
    private fun cdnPriority(url: String, ispTag: String?): Int {
        var priority = 0

        // 咪咕CDN → 移动自有，不被屏蔽
        if (url.contains("miguvideo.com")) priority += 100
        // sys_前缀 → 系统播放器，绕DNS缓存
        if (url.startsWith("sys_")) priority += 80
        // $Y标签 → 移动专用CDN
        if (ispTag == "Y") priority += 60
        // 腾讯CDN
        if (url.contains("video.qq.com")) priority += 30
        // 抖音CDN
        if (url.contains("douyincdn.com")) priority += 20
        // CCTV CDN
        if (url.contains("cctv.cn") || url.contains("cntv.cn")) priority += 10

        return priority
    }

    /**
     * 解析APK的gzip+JSON+AES加密频道数据
     *
     * @param rawData gzip压缩的原始字节
     * @param source 数据源信息
     * @param ispTag 当前运营商标签 ("Y"=移动, "D"=电信, "L"=联通, null=未知)
     * @return 按ISP过滤后的频道列表
     */
    fun parse(rawData: ByteArray, source: Source, ispTag: String? = null): List<Channel> {
        try {
            // 1. gzip解压
            val jsonBytes = ByteArrayOutputStream().use { out ->
                GZIPInputStream(ByteArrayInputStream(rawData)).use { gz ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (gz.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val jsonStr = String(jsonBytes, Charsets.UTF_8)

            // 2. 解析JSON
            val root = JSONObject(jsonStr)
            if (root.optInt("status") != 0) {
                Log.w(TAG, "APK data status error: ${root.optInt("status")}")
                return emptyList()
            }

            val dataArray = root.optJSONArray("data") ?: return emptyList()

            // 3. 检测IPv6支持（对应APK的App.g）
            val ipv6Supported = detectIpv6Support()

            val channels = mutableListOf<Channel>()

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val title = item.optString("title", "").trim()
                val province = item.optString("province", "")
                val ctype = item.optInt("ctype", -1)
                val urlsArray = item.optJSONArray("urls") ?: continue

                if (title.isBlank()) continue

                // 4. 解密+ISP过滤所有URL（按APK的Channel.setUrls逻辑）
                val decryptedUrls = mutableListOf<Pair<String, Int>>()  // (url, priority)
                for (j in 0 until urlsArray.length()) {
                    val encUrl = urlsArray.getString(j)
                    val decUrl = decryptAndFilter(encUrl, ispTag, ipv6Supported)
                    if (decUrl != null && decUrl.isNotBlank()) {
                        val priority = cdnPriority(decUrl, ispTag)
                        decryptedUrls.add(Pair(decUrl, priority))
                    }
                }

                if (decryptedUrls.isEmpty()) continue

                // 5. 按CDN优先级排序（移动网络优化）
                decryptedUrls.sortByDescending { it.second }

                // 6. 分类分组
                val group = classifyGroup(province, title)

                // 7. 创建Channel
                // 主URL = 优先级最高的URL
                // backupUrls = 其余URL（保留协议前缀，播放时由resolveForPlayback解析）
                val primaryUrl = decryptedUrls[0].first
                val backups = if (decryptedUrls.size > 1) {
                    decryptedUrls.drop(1).take(5).joinToString("|") { it.first }
                } else ""

                channels.add(Channel(
                    name = title,
                    url = primaryUrl,
                    group = group,
                    tvgName = title,
                    sourceId = source.id,
                    backupUrls = backups,
                    ctype = ctype
                ))
            }

            Log.i(TAG, "Parsed ${channels.size} channels from APK data (ISP: ${ispTag ?: "unknown"}, IPv6: $ipv6Supported)")
            return channels

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse APK channel data", e)
            return emptyList()
        }
    }

    /** 检测设备是否支持IPv6（对应APK的App.g = com.jerry.live.tv.utils.q.b()） */
    private fun detectIpv6Support(): Boolean {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.any { iface ->
                iface.interfaceAddresses?.any { it.address is java.net.Inet6Address } == true
            } == true
        } catch (e: Exception) {
            false
        }
    }

    /** 检测数据是否为APK的gzip+JSON格式 */
    fun isApkData(data: ByteArray): Boolean {
        return data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
    }

    private fun classifyGroup(province: String, name: String): String {
        val n = name.lowercase()
        val p = province.lowercase()
        return when {
            n.contains("cctv") || n.contains("央视") || n.contains("中央") || p.contains("央视") -> Channel.GROUP_CCTV
            n.contains("卫视") || p.contains("卫视") -> Channel.GROUP_SATELLITE
            n.contains("香港") || n.contains("tvb") || n.contains("澳门") || n.contains("台湾") -> Channel.GROUP_HK_MACAO_TW
            n.contains("nhk") || n.contains("bbc") || n.contains("cnn") || n.contains("cgtn") -> Channel.GROUP_INTERNATIONAL
            p.contains("超清") -> Channel.GROUP_OTHER
            "地区" in province -> province.replace("地区", "")
            else -> Channel.GROUP_LOCAL
        }
    }
}
