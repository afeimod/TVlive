package com.tvlive.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject

/**
 * 运营商（ISP）检测器
 *
 * 参考电视直播应用的反屏蔽策略：检测当前网络运营商类型，
 * 以便针对不同运营商做差异化优化：
 *
 * - **中国移动（CMCC）**：DNS 污染最严重，GitHub 域名被全面封锁，
 *   境外 CDN 流大量被 IP 层屏蔽，需要最多的镜像/代理/IPv6 回退
 * - **中国电信（China Telecom）**：封锁相对较少，部分境外 CDN 可直连
 * - **中国联通（China Unicom）**：与电信类似，封锁较少
 * - **未知/其他**：按移动网络最保守策略处理（确保可用性）
 *
 * 检测方式：
 * 1. 通过 IP 归属地查询服务获取运营商信息（两个服务互备）
 * 2. 伪装桌面浏览器 User-Agent 避免被服务端拒绝
 * 3. 检测结果缓存 30 分钟（运营商不会频繁变化）
 */
object ISPDetector {

    private const val TAG = "ISPDetector"

    /** 缓存有效期：30 分钟 */
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    /** 运营商类型 */
    enum class ISPType(val code: String, val label: String) {
        CMCC("Y", "中国移动"),       // 移动/移通
        TELECOM("D", "中国电信"),     // 电信
        UNICOM("L", "中国联通"),      // 联通
        UNKNOWN("", "未知")
    }

    /** 当前检测到的运营商 */
    @Volatile
    var currentISP: ISPType = ISPType.UNKNOWN
        private set

    /** 当前网络类型 */
    @Volatile
    var currentNetworkType: NetworkType = NetworkType.UNKNOWN
        private set

    /** 检测时间戳 */
    @Volatile
    private var lastDetectTime: Long = 0

    /** 网络类型 */
    enum class NetworkType {
        WIFI,       // Wi-Fi（通常不受运营商封锁影响，但 DNS 可能仍被污染）
        MOBILE_5G,  // 5G 移动网络
        MOBILE_4G,  // 4G 移动网络
        ETHERNET,   // 以太网（电视/机顶盒）
        UNKNOWN     // 未知
    }

    /**
     * 检测当前运营商
     *
     * 如果缓存未过期且已检测过，直接返回缓存结果。
     * 否则并发请求两个 IP 归属地服务，先成功者胜。
     *
     * @param context Android Context
     * @param forceRefresh 是否强制刷新（忽略缓存）
     * @return 检测到的运营商类型
     */
    suspend fun detect(context: Context, forceRefresh: Boolean = false): ISPType {
        // 缓存有效且非强制刷新
        if (!forceRefresh && currentISP != ISPType.UNKNOWN &&
            System.currentTimeMillis() - lastDetectTime < CACHE_TTL_MS) {
            Log.d(TAG, "ISP cache hit: ${currentISP.label}")
            return currentISP
        }

        // 先检测网络类型
        detectNetworkType(context)

        // Wi-Fi 网络下封锁较轻，但仍需检测运营商（DNS 可能被路由器污染）
        try {
            val result = detectISPFromNetwork()
            currentISP = result
            lastDetectTime = System.currentTimeMillis()
            Log.i(TAG, "ISP detected: ${result.label} (network: ${currentNetworkType})")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "ISP detection failed: ${e.message}")
            return currentISP
        }
    }

    /**
     * 检测当前网络类型
     */
    private fun detectNetworkType(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }

            currentNetworkType = when {
                caps == null -> NetworkType.UNKNOWN
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    // 尝试区分 5G/4G（Android 12+ 可通过 NetworkCapabilities 检测）
                    // 简化处理：统一标记为 MOBILE（具体 5G/4G 对策略影响不大）
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        // Android 12+ 可通过 linkDownstreamBandwidthKbps 粗略判断
                        val bandwidth = caps.linkDownstreamBandwidthKbps
                        if (bandwidth > 100_000) NetworkType.MOBILE_5G  // >100Mbps 疑似 5G
                        else NetworkType.MOBILE_4G
                    } else {
                        NetworkType.MOBILE_4G
                    }
                }
                else -> NetworkType.UNKNOWN
            }
        } catch (e: Exception) {
            currentNetworkType = NetworkType.UNKNOWN
        }
    }

    /**
     * 通过网络请求检测运营商（并发两个服务，先成功者胜）
     */
    private suspend fun detectISPFromNetwork(): ISPType = withContext(Dispatchers.IO) {
        val httpClient = HttpClientProvider.dataClient

        // 桌面浏览器 User-Agent 伪装（避免被服务端拒绝 TV 设备请求）
        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val result = CompletableDeferred<ISPType>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 服务 1：pconline IP 归属地
        val job1 = scope.async {
            try {
                val request = Request.Builder()
                    .url("http://whois.pconline.com.cn/ipJson.jsp")
                    .header("User-Agent", desktopUA)
                    .build()
                val body = httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
                if (body != null) {
                    // pconline 返回 JSON：{"ip":"x.x.x.x","pro":"广东省","proCode":"440000","city":"深圳市","cityCode":"440300","region":"","regionCode":"0","addr":"广东省深圳市 移动","addrSorted":""}
                    val json = JSONObject(body)
                    val addr = json.optString("addr", "")
                    val pro = json.optString("pro", "")
                    val isp = classifyISP(addr + pro)
                    if (isp != ISPType.UNKNOWN) {
                        Log.d(TAG, "Detected ISP via pconline: $isp ($addr)")
                        result.complete(isp)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pconline ISP detection failed: ${e.message}")
            }
        }

        // 服务 2：ip-api.com（海外服务，作为备用）
        val job2 = scope.async {
            try {
                val request = Request.Builder()
                    .url("http://ip-api.com/json/?lang=zh-CN")
                    .header("User-Agent", desktopUA)
                    .build()
                val body = httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
                if (body != null) {
                    // ip-api.com 返回：{"isp":"China Mobile","org":"China Mobile Communications Corporation",...}
                    val json = JSONObject(body)
                    val ispStr = json.optString("isp", "") + " " + json.optString("org", "")
                    val isp = classifyISP(ispStr)
                    if (isp != ISPType.UNKNOWN) {
                        Log.d(TAG, "Detected ISP via ip-api: $isp ($ispStr)")
                        result.complete(isp)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ip-api ISP detection failed: ${e.message}")
            }
        }

        // 等待结果（最长 5 秒）
        try {
            withTimeout(5000L) {
                try {
                    job1.await()
                    job2.await()
                } catch (_: Exception) {}
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {}

        if (!result.isCompleted) {
            result.complete(ISPType.UNKNOWN)
        }

        scope.cancel()
        result.await()
    }

    /**
     * 根据地址/运营商字符串分类
     */
    private fun classifyISP(text: String): ISPType {
        val lower = text.lowercase()
        return when {
            lower.contains("移动") || lower.contains("移通") || lower.contains("cmcc") ||
            lower.contains("china mobile") || lower.contains("cmnet") -> ISPType.CMCC
            lower.contains("电信") || lower.contains("chinatelecom") ||
            lower.contains("china telecom") || lower.contains("chinanet") -> ISPType.TELECOM
            lower.contains("联通") || lower.contains("unicom") ||
            lower.contains("china unicom") || lower.contains("chinaunicom") -> ISPType.UNICOM
            else -> ISPType.UNKNOWN
        }
    }

    /**
     * 是否为中国移动网络（或未知 - 按最保守策略处理）
     *
     * 关键设计：未知运营商默认按移动网络策略处理，
     * 因为移动网络封锁最严，按此策略对电信/联通用户也无害（只是多了不必要的镜像尝试）
     */
    fun isCMCCOrUnknown(): Boolean = currentISP == ISPType.CMCC || currentISP == ISPType.UNKNOWN

    /**
     * 是否为 Wi-Fi 网络
     */
    fun isWifi(): Boolean = currentNetworkType == NetworkType.WIFI || currentNetworkType == NetworkType.ETHERNET

    /**
     * 获取运营商信息描述
     */
    fun getISPInfo(): String = "${currentISP.label} / ${currentNetworkType.name}"
}
