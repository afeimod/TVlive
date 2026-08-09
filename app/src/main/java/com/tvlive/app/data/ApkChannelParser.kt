package com.tvlive.app.data

import android.util.Log
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.Source
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * APK 加密频道数据解析器
 *
 * 解析电视直播APK的 dszb3.gz 数据格式：
 * 1. gzip 解压 → JSON
 * 2. JSON 中每个频道的 urls 数组包含 AES-128-CBC 加密的 URL
 * 3. 使用从 APK 反编译提取的密钥解密
 * 4. 支持 ISP 标签（$Y=移动, $D=电信, $L=联通, $i6=IPv6）
 * 5. 协议前缀处理：sys_http:// → http://, ikkHeaders:// → Headers:// 等
 *
 * 密钥来源：jadx 反编译 → libjerry.so JNI → getJniString() → "you!je@19rr$20y#"
 */
object ApkChannelParser {

    private const val TAG = "ApkChannelParser"

    /** AES 解密 */
    private fun decryptUrl(encryptedBase64: String, ispTag: String? = null): String? {
        return try {
            val raw = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
            val keySpec = SecretKeySpec(DefaultSources.AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(DefaultSources.AES_IV_BYTES)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(raw)
            var url = String(decrypted, Charsets.UTF_8)

            // 处理 ISP 标签
            // $Y = 中国移动专用CDN, $D = 中国电信, $L = 中国联通, $i6 = IPv6
            if (url.contains("$")) {
                val parts = url.split("$", limit = 2)
                url = parts[0]
                val tag = parts.getOrNull(1) ?: ""

                // ISP 标签过滤：只保留匹配当前运营商的URL
                if (ispTag != null && tag.isNotEmpty()) {
                    val currentTag = ispTag
                    val isMatch = when {
                        tag.startsWith("Y") && currentTag == "Y" -> true
                        tag.startsWith("D") && currentTag == "D" -> true
                        tag.startsWith("L") && currentTag == "L" -> true
                        tag.startsWith("i6") -> true  // IPv6 URL 始终保留
                        else -> false
                    }
                    if (!isMatch && tag.length <= 2) {
                        return null  // 不匹配当前ISP，丢弃此URL
                    }
                }
            }

            // 处理协议前缀
            url = when {
                url.startsWith("sys_http://") -> "http://" + url.removePrefix("sys_http://")
                url.startsWith("sys_https://") -> "https://" + url.removePrefix("sys_https://")
                url.startsWith("sys_yscj://") -> url.removePrefix("sys_")
                url.startsWith("sys_miguak://") -> return null  // 咪咕AK协议，无法直接播放
                url.startsWith("sys_ysgf://") -> return null
                url.startsWith("miguytv://") -> return null  // 需要咪咕SDK
                url.startsWith("migutv2://") -> return null
                url.startsWith("migutv3://") -> return null
                url.startsWith("dytv://") -> return null  // 抖音协议
                url.startsWith("gwpd") -> return null  // 付费频道，需要单独解析
                url.startsWith("ccto://") -> return null  // CCTV OTT协议
                url.startsWith("yscj://") -> return null  // 云视超清协议
                url.startsWith("ysnew://") -> return null  // 新云视协议
                url.startsWith("ysgf://") -> return null
                url.startsWith("sxyqgd://") -> return null
                url.startsWith("hfbtv2://") -> return null
                else -> url
            }

            // 只保留可直接播放的 HTTP/HTTPS URL
            if (url.startsWith("http://") || url.startsWith("https://")) url else null
        } catch (e: Exception) {
            Log.w(TAG, "Decrypt failed: ${e.message}")
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

                // 3. 解密所有 URL
                val decryptedUrls = mutableListOf<String>()
                for (j in 0 until urlsArray.length()) {
                    val encUrl = urlsArray.getString(j)
                    val decUrl = decryptUrl(encUrl, ispTag)
                    if (decUrl != null && decUrl.isNotBlank()) {
                        decryptedUrls.add(decUrl)
                    }
                }

                if (decryptedUrls.isEmpty()) continue

                // 4. 分类分组
                val group = classifyGroup(province, title)

                // 5. 创建 Channel（主URL + backupUrls）
                val primaryUrl = decryptedUrls[0]
                val backups = if (decryptedUrls.size > 1) {
                    decryptedUrls.drop(1).joinToString("|")
                } else ""

                channels.add(Channel(
                    name = title,
                    url = primaryUrl,
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
