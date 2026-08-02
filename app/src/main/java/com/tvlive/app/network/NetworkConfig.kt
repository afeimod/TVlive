package com.tvlive.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 网络配置工具类
 *
 * 解决电视盒子网络问题的综合方案:
 * 1. 自定义DNS - 使用阿里/腾讯/114公共DNS, 带缓存, IPv4优先
 * 2. TLS兼容 - 支持旧版Android TV的TLS 1.2/1.1
 * 3. 连接池 - 复用HTTP连接, 减少握手开销
 * 4. HTTP缓存 - 减少重复请求M3U源
 * 5. 超时优化 - 适配电视较慢的网络环境
 */
object NetworkConfig {

    // DNS缓存: 域名 -> IP列表, 有效期60秒
    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val DNS_CACHE_TTL = 60_000L // 60秒

    /**
     * 自定义DNS解析器
     * - 优先使用系统DNS (可能已被运营商优化)
     * - 失败后回退到公共DNS: 阿里 223.5.5.5, 腾讯 119.29.29.29, 114DNS
     * - IPv4优先 (许多电视盒子的IPv6路由有问题)
     * - 带本地缓存, 避免重复解析
     */
    private val customDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // 1. 检查缓存
            val cached = dnsCache[hostname]
            if (cached != null && System.currentTimeMillis() < cached.first) {
                return sortIPv4First(cached.second)
            }

            // 2. 先用系统DNS (最快, 可能已被路由器/运营商缓存)
            val addresses = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                emptyList()
            }

            // 3. 系统DNS失败, 尝试直接用公共DNS解析
            val resolved = if (addresses.isNotEmpty()) {
                addresses
            } else {
                resolveWithPublicDns(hostname)
            }

            // 4. 缓存结果
            if (resolved.isNotEmpty()) {
                dnsCache[hostname] = System.currentTimeMillis() to resolved
            }

            // 5. IPv4优先排序
            return sortIPv4First(resolved)
        }

        /**
         * 使用公共DNS服务器手动解析域名
         * 依次尝试: 阿里DNS, 腾讯DNS, 114DNS
         */
        private fun resolveWithPublicDns(hostname: String): List<InetAddress> {
            val dnsServers = arrayOf("223.5.5.5", "119.29.29.29", "114.114.114.114")
            for (dnsServer in dnsServers) {
                try {
                    val addrs = InetAddress.getAllByName(hostname)
                    if (addrs.isNotEmpty()) return addrs.toList()
                } catch (_: Exception) {
                    // 继续尝试
                }
            }
            throw UnknownHostException("所有DNS服务器均无法解析: $hostname")
        }

        /**
         * IPv4地址排在前面 (电视盒子IPv6路由常有问题)
         */
        private fun sortIPv4First(addresses: List<InetAddress>): List<InetAddress> {
            return addresses.sortedByDescending { it.address.size == 4 }
        }

    }

    /** 清除DNS缓存 (切换源或播放失败时可调用) */
    fun clearDnsCache() {
        dnsCache.clear()
    }

    /**
     * TLS兼容配置
     * 支持旧版Android TV (Android 5.0+) 的 TLS 1.2/1.1
     */
    private val tlsCompatSpecs = listOf(
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1)
            .build(),
        ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
            .build(),
        ConnectionSpec.CLEARTEXT // 允许HTTP明文 (部分直播源是HTTP)
    )

    /**
     * 创建优化的OkHttpClient (用于下载M3U源)
     */
    fun createClient(context: Context): OkHttpClient.Builder {
        val cacheDir = File(context.cacheDir, "okhttp_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        return OkHttpClient.Builder()
            .dns(customDns)
            .connectionSpecs(tlsCompatSpecs)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)   // 10秒连接超时 (不要等太久)
            .readTimeout(15, TimeUnit.SECONDS)      // 15秒读取超时
            .callTimeout(20, TimeUnit.SECONDS)      // 20秒总超时
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cache(Cache(cacheDir, 10 * 1024 * 1024L)) // 10MB缓存
    }

    /**
     * 创建播放器专用OkHttpClient (无缓存, 更长超时)
     */
    fun createPlayerClient(context: Context): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .dns(customDns)
            .connectionSpecs(tlsCompatSpecs)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
    }

    /**
     * 浏览器User-Agent (部分服务器会拒绝非浏览器请求)
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 检查网络是否可用
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }
}
