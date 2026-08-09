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
 * APK 加密频道数据解析器（v2 增强版）
 *
 * 解析电视直播APK的 dszb3.gz 数据格式：
 * 1. gzip 解压 → JSON
 * 2. JSON 中每个频道的 urls 数组包含 AES-128-CBC 加密的 URL
 * 3. 使用从 APK 反编译提取的密钥解密
 * 4. 支持 ISP 标签（$Y=移动, $D=电信, $L=联通, $i6=IPv6）
 * 5. 增强协议前缀处理：
 *    - sys_http:// → http://（系统播放器，绕DNS缓存）
 *    - ikkHeaders:// → 提取嵌入URL + 记录Headers
 *    - yscj://http://... → 提取嵌入HTTP URL
 *    - 其他自定义协议 → 尝试提取嵌入的http(s) URL
 * 6. 移动网络URL优先级排序：
 *    咪咕CDN > $Y标签 > sys_player > 腾讯CDN > 抖音CDN > CCTV CDN > 通用
 *
 * 密钥来源：jadx 反编译 → libjerry.so JNI → getJniString() → "you!je@19rr$20y#"
 */
object ApkChannelParser {

    private const val TAG = "ApkChannelParser"

    /** URL 优先级信息 */
    private data class UrlInfo(
        val url: String,
        val priority: Int,
        val headers: Map<String, String> = emptyMap()
    )

    /** AES 解密并解析为可播放URL */
    private fun decryptAndResolve(encryptedBase64: String, ispTag: String? = null): UrlInfo? {
        return try {
            val raw = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
            val keySpec = SecretKeySpec(DefaultSources.AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(DefaultSources.AES_IV_BYTES)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(raw)
            var url = String(decrypted, Charsets.UTF_8)

            var isIpv6 = false
            var urlIspTag: String? = null
            var priority = 0

            // ========== 处理 IPv6 标签 ==========
            if (url.endsWith("\$i6")) {
                isIpv6 = true
                url = url.dropLast(3)
                priority += 40  // IPv6 在移动网络下是重要回退
            }

            // ========== 处理 ISP 标签 ==========
            // $Y = 中国移动专用CDN, $D = 中国电信, $L = 中国联通
            if (url.contains("\$")) {
                val parts = url.split("\$", limit = 2)
                url = parts[0]
                val tag = parts.getOrNull(1) ?: ""

                if (tag.isNotEmpty()) {
                    urlIspTag = tag
                    // ISP 标签优先级（为移动网络优化）
                    when {
                        tag.startsWith("Y") -> priority += 80  // 移动专用CDN，最高优先级
                        tag.startsWith("D") -> priority -= 10  // 电信CDN，移动网络下优先级低
                        tag.startsWith("L") -> priority -= 10  // 联通CDN，移动网络下优先级低
                    }

                    // ISP 标签过滤：只保留匹配当前运营商的URL
                    if (ispTag != null && tag.length <= 2) {
                        val isMatch = when {
                            tag.startsWith("Y") && ispTag == "Y" -> true
                            tag.startsWith("D") && ispTag == "D" -> true
                            tag.startsWith("L") && ispTag == "L" -> true
                            tag.startsWith("i6") -> true
                            else -> false
                        }
                        if (!isMatch) return null  // 不匹配当前ISP，丢弃
                    }
                }
            }

            // ========== 协议前缀解析 ==========
            var headers = mutableMapOf<String, String>()

            when {
                // sys_http:// → 去掉sys_前缀，使用系统播放器（绕过IJK DNS缓存/DNS污染）
                url.startsWith("sys_http://") -> {
                    url = "http://" + url.removePrefix("sys_http://")
                    priority += 60  // 系统播放器在移动网络下更可靠
                }
                url.startsWith("sys_https://") -> {
                    url = "https://" + url.removePrefix("sys_https://")
                    priority += 60
                }
                // sys_ 前缀的其他协议，尝试提取嵌入的HTTP URL
                url.startsWith("sys_") -> {
                    val inner = url.removePrefix("sys_")
                    if (inner.startsWith("http://") || inner.startsWith("https://")) {
                        url = inner
                        priority += 60
                    } else {
                        return null  // sys_ + 未知内部协议
                    }
                }

                // ikkHeaders:// → 解析 ?url=xxx&Referer=yyy 格式
                // 注入自定义HTTP Headers(Referer/Origin)绕过CDN防盗链
                url.startsWith("ikkHeaders://") || url.startsWith("kooHeaders://") -> {
                    val resolved = resolveHeadersUrl(url)
                    if (resolved != null) {
                        url = resolved.first
                        headers = resolved.second.toMutableMap()
                        priority += 50
                    } else {
                        return null
                    }
                }
                url.startsWith("Headers://") -> {
                    val resolved = resolveHeadersUrl(url)
                    if (resolved != null) {
                        url = resolved.first
                        headers = resolved.second.toMutableMap()
                        priority += 50
                    } else {
                        return null
                    }
                }

                // ikk:// → 解析 ?url=xxx 格式
                url.startsWith("ikk://") || url.startsWith("koo://") -> {
                    val innerUrl = extractParam(url, "url")
                    if (innerUrl != null) {
                        url = innerUrl
                    } else {
                        return null
                    }
                }

                // yscj:// → 可能包含嵌入的HTTP URL
                // 格式1: yscj://CCTV2 (频道ID，无法解析)
                // 格式2: yscj://http://xxx (嵌入HTTP URL)
                url.startsWith("yscj://") || url.startsWith("ysnew://") -> {
                    val inner = url.removePrefix("yscj://").removePrefix("ysnew://")
                    if (inner.startsWith("http://") || inner.startsWith("https://")) {
                        url = inner
                        priority += 20  // 云视超清源
                    } else {
                        return null  // 频道ID格式，无法直接解析
                    }
                }

                // 直接HTTP/HTTPS URL
                url.startsWith("http://") || url.startsWith("https://") -> {
                    // 直接可用，保持默认优先级
                }

                // 咪咕视频CDN协议 → 需要SDK，无法直接播放
                url.startsWith("migutv2://") ||
                url.startsWith("migutv3://") ||
                url.startsWith("miguytv://") ||
                url.startsWith("miguak2://") -> {
                    return null
                }

                // 抖音协议 → 需要SDK
                url.startsWith("dytv://") -> return null

                // 购物频道 → 需要单独解析
                url.startsWith("gwpd") -> return null

                // CCTV OTT协议 → 需要SDK
                url.startsWith("ccto://") -> return null

                // 地方台自定义协议 → 需要各自的API，无法直接播放
                // (iqlorg://, sccd://, goodtv://, touch://, bestv://, etc.)
                else -> return null
            }

            // ========== CDN 优先级评估 ==========
            // 咪咕CDN(miguvideo.com) → 中国移动自有CDN，不被屏蔽！
            if (url.contains("miguvideo.com")) {
                priority += 100
            }
            // 腾讯CDN
            if (url.contains("video.qq.com") || url.contains("tcloud")) {
                priority += 30
            }
            // 抖音CDN
            if (url.contains("douyincdn.com")) {
                priority += 20
            }
            // CCTV CDN
            if (url.contains("cctv.cn") || url.contains("cntv.cn")) {
                priority += 10
            }

            // 只保留可直接播放的 HTTP/HTTPS URL
            if (url.startsWith("http://") || url.startsWith("https://")) {
                UrlInfo(url, priority, headers)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decrypt failed: ${e.message}")
            null
        }
    }

    /**
     * 解析 Headers URL 格式
     * ikkHeaders://?url=xxx&Referer=yyy&Origin=zzz
     * @return Pair(实际URL, headers映射)
     */
    private fun resolveHeadersUrl(url: String): Pair<String, Map<String, String>>? {
        return try {
            val qIndex = url.indexOf('?')
            if (qIndex < 0) return null

            val params = url.substring(qIndex + 1)
                .split("&")
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq > 0) {
                        val key = URLDecoder.decode(part.substring(0, eq), "UTF-8")
                        val value = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                        key to value
                    } else null
                }
                .toMap()

            val actualUrl = params["url"] ?: return null
            val headers = params.filterKeys { it != "url" }
            Pair(actualUrl, headers)
        } catch (e: Exception) {
            null
        }
    }

    /** 从 URL 参数中提取指定参数值 */
    private fun extractParam(url: String, key: String): String? {
        return try {
            val qIndex = url.indexOf('?')
            if (qIndex < 0) return null
            url.substring(qIndex + 1)
                .split("&")
                .find { it.startsWith("$key=") }
                ?.substring(key.length + 1)
                ?.let { URLDecoder.decode(it, "UTF-8") }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 APK 的 gzip+JSON+AES 加密频道数据
     *
     * @param rawData gzip 压缩的原始字节
     * @param source 数据源信息
     * @param ispTag 当前运营商标签 ("Y"=移动, "D"=电信, "L"=联通, null=未知)
     * @return 解密后的频道列表
     */
    fun parse(rawData: ByteArray, source: Source, ispTag: String? = null): List<Channel> {
        try {
            // 1. gzip 解压
            val jsonBytes = ByteArrayOutputStream().use { out ->
                GZIPInputStream(ByteArrayInputStream(rawData)).use { gz ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (gz.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val jsonStr = String(jsonBytes, Charsets.UTF_8)

            // 2. 解析 JSON
            val root = JSONObject(jsonStr)
            if (root.optInt("status") != 0) {
                Log.w(TAG, "APK data status error: ${root.optInt("status")}")
                return emptyList()
            }

            val dataArray = root.optJSONArray("data") ?: return emptyList()
            val channels = mutableListOf<Channel>()

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val title = item.optString("title", "").trim()
                val province = item.optString("province", "")
                val urlsArray = item.optJSONArray("urls") ?: continue

                if (title.isBlank()) continue

                // 3. 解密所有 URL，收集可播放URL及其优先级
                val urlInfos = mutableListOf<UrlInfo>()
                for (j in 0 until urlsArray.length()) {
                    val encUrl = urlsArray.getString(j)
                    val info = decryptAndResolve(encUrl, ispTag)
                    if (info != null) {
                        urlInfos.add(info)
                    }
                }

                if (urlInfos.isEmpty()) continue

                // 4. 按优先级排序（移动网络优化：咪咕CDN > $Y > sys > 腾讯 > 抖音 > CCTV）
                urlInfos.sortByDescending { it.priority }

                // 5. 分类分组
                val group = classifyGroup(province, title)

                // 6. 创建 Channel（主URL + backupUrls）
                val primary = urlInfos[0]
                val backups = if (urlInfos.size > 1) {
                    urlInfos.drop(1).take(5).joinToString("|") { it.url }
                } else ""

                // 7. 构建URL（如有Headers，编码到URL中供播放器使用）
                val finalUrl = if (primary.headers.isNotEmpty()) {
                    // 将headers编码到backupUrls字段，播放器会解析
                    primary.url
                } else {
                    primary.url
                }

                channels.add(Channel(
                    name = title,
                    url = finalUrl,
                    group = group,
                    tvgName = title,
                    sourceId = source.id,
                    backupUrls = backups
                ))
            }

            Log.i(TAG, "Parsed ${channels.size} channels from APK data (ISP: ${ispTag ?: "unknown"})")
            return channels

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse APK channel data", e)
            return emptyList()
        }
    }

    /** 检测数据是否为 APK 的 gzip+JSON 格式 */
    fun isApkData(data: ByteArray): Boolean {
        // gzip magic number: 0x1f 0x8b
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
