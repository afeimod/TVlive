package com.tvlive.app.net

import android.util.Log
import okhttp3.Dns
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 自定义 DNS 解析器
 *
 * 解决中国移动网络 DNS 污染/劫持问题。
 *
 * 四层解析策略：
 * 1. 系统 DNS - 对于国内域名（非污染域名），系统 DNS 通常可用
 * 2. 公共 DNS (UDP) - 直连阿里/腾讯 DNS 服务器，绕过运营商 DNS
 * 3. DoH (DNS over HTTPS) - 通过 HTTPS 查询 DNS，绕过 UDP 53 端口封锁
 * 4. 硬编码 IP - 对已知 DoH 服务器域名，使用预置 IP 避免 DNS 鸡蛋问题
 *
 * 对于已知被封锁的域名（github 等），跳过系统 DNS 直接使用公共 DNS。
 * 对于国内域名，不过滤私有 IP（运营商 CDN 可能使用内网地址）。
 */
class SafeDns : Dns {

    companion object {
        private const val TAG = "SafeDns"

        /** 公共 DNS 服务器列表（UDP 查询） */
        private val PUBLIC_DNS_SERVERS = arrayOf(
            "223.5.5.5",      // 阿里 DNS
            "119.29.29.29",   // 腾讯 DNS
            "223.6.6.6",      // 阿里 DNS 备用
            "114.114.114.114"  // 114DNS
        )

        /**
         * DoH 服务器列表（DNS over HTTPS）
         * 格式: 域名 -> 预置 IP 列表（避免 DNS 鸡蛋问题）
         */
        private val DOH_SERVERS = listOf(
            DohServer("dns.alidns.com", "/resolve", listOf("223.5.5.5", "223.6.6.6")),
            DohServer("doh.pub", "/dns-query", listOf("119.29.29.29", "119.28.28.28"))
        )

        /** DNS 查询超时（毫秒）- 5G 网络下缩短，并行查询下足够 */
        private const val DNS_TIMEOUT_MS = 2000

        /** DoH 查询超时（毫秒）- 5G 网络下缩短 */
        private const val DOH_TIMEOUT_MS = 3000

        /** DNS 缓存有效期（秒）- 缩短到 2 分钟，更频繁刷新以适应移动网络 IP 变化 */
        private const val CACHE_TTL_SECONDS = 120L

        /**
         * 已知被中国移动 DNS 污染的域名后缀
         * 这些域名跳过系统 DNS，直接使用公共 DNS
         */
        private val POLLUTED_DOMAINS = setOf(
            "githubusercontent.com",
            "github.io",
            "github.com",
            "gist.github.com",
            "objects.githubusercontent.com",
            "raw.githubusercontent.com",
            "cdn.jsdelivr.net"  // 自2022年起被DNS污染
        )

        /**
         * 国内域名后缀 - 对于这些域名，系统 DNS 通常可靠
         * 且不过滤私有 IP（运营商 CDN 可能使用内网地址）
         *
         * 包含所有内置镜像源使用的国内域名，确保 DNS 解析优先使用系统 DNS（最快）
         */
        private val DOMESTIC_DOMAIN_SUFFIXES = setOf(
            ".cn", ".com.cn", ".top", ".net", ".org", ".edu.cn",
            "alidns.com", "doh.pub",
            "zbds.top", "fanmingming.com", "kuaikan123.cyou",
            "tuna.tsinghua.edu.cn",
            "gitmirror.com", "zzko.cn",
            "kkgithub.com", "kgithub.com",
            "ghproxy.net", "gh-proxy.com", "ghproxy.cc",
            "staticdn.net",
            "jsdelivr.net",  // gcore/testingcf/fastly 等 jsdelivr 镜像
            "gcore.com", "gcorecdn.com",
            "cloudflare.com"
        )
    }

    /** DoH 服务器信息 */
    private data class DohServer(
        val domain: String,
        val path: String,
        val ips: List<String>
    )

    /**
     * 关键镜像域名的硬编码 IP（5G 中国移动网络深度优化）
     *
     * 当所有 DNS 查询都失败时（极端情况），直接使用这些 IP 建立 HTTPS 连接。
     * IPs 来自公共 DNS 解析的真实 Cloudflare/CDN 节点，定期可更新。
     *
     * 注：OkHttp 使用这些 IP 时仍会发送正确的 Host header 和 SNI，
     * TLS 握手时 SNI 字段会告诉服务器真实域名，因此 HTTPS 证书验证仍能通过。
     */
    private val HARDCODED_IPS = mapOf(
        "gcore.jsdelivr.net" to listOf("104.17.207.5", "104.17.208.5"),
        "testingcf.jsdelivr.net" to listOf("104.17.207.5", "104.17.208.5"),
        "fastly.jsdelivr.net" to listOf("104.17.207.5", "104.17.208.5")
    )

    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    /**
     * ★★★ 清空DNS缓存（对应APK的 dns_cache_clear=1）★★★
     *
     * IJK Player在每次播放前都会清空DNS缓存（dns_cache_clear=1），
     * 防止被污染的DNS结果被复用。此方法清除SafeDns的内部缓存，
     * 确保下一次DNS查询走全新的解析路径。
     */
    fun clearCache() {
        cache.clear()
        Log.d(TAG, "SafeDns cache cleared")
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. 检查缓存
        cache[hostname]?.let { (addresses, expireAt) ->
            if (System.currentTimeMillis() < expireAt && addresses.isNotEmpty()) {
                Log.d(TAG, "DNS cache hit: $hostname -> ${addresses.map { it.hostAddress }}")
                return addresses
            }
        }

        val isPolluted = isPollutedDomain(hostname)
        val isDomestic = isDomesticDomain(hostname)

        // 2. 对于非污染域名，先尝试系统 DNS（5G 网络下系统 DNS 通常可靠且最快）
        if (!isPolluted) {
            try {
                val systemResult = Dns.SYSTEM.lookup(hostname)
                val valid = if (isDomestic) {
                    systemResult.filter { it.hostAddress != null && it.hostAddress != "0.0.0.0" }
                } else {
                    systemResult.filter { isValidIp(it.hostAddress) }
                }
                if (valid.isNotEmpty()) {
                    cacheResult(hostname, valid)
                    return valid
                }
            } catch (e: Exception) {
                Log.w(TAG, "System DNS failed for $hostname: ${e.message}")
            }
        }

        // 3. 并行查询所有公共 DNS（UDP）+ DoH，取第一个成功结果
        //    5G 网络优化：不再顺序逐个尝试，而是同时发起所有查询
        //    最快的 DNS 服务器决定整体响应时间（通常 50-200ms）
        val parallelResult = resolveViaParallelDns(hostname, isDomestic)
        if (parallelResult.isNotEmpty()) {
            cacheResult(hostname, parallelResult)
            return parallelResult
        }

        // 4. 所有方法都失败，最后尝试系统 DNS（作为兜底）
        if (isPolluted) {
            try {
                val systemResult = Dns.SYSTEM.lookup(hostname)
                val valid = if (isDomestic) {
                    systemResult.filter { it.hostAddress != null && it.hostAddress != "0.0.0.0" }
                } else {
                    systemResult.filter { isValidIp(it.hostAddress) }
                }
                if (valid.isNotEmpty()) {
                    Log.w(TAG, "Fallback to system DNS for $hostname")
                    cacheResult(hostname, valid)
                    return valid
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        // 5. 终极兜底：使用硬编码 IP（针对关键镜像域名）
        //    当所有 DNS 服务器都不可达时，直接返回预置 IP（OkHttp 会用 SNI 处理 HTTPS）
        HARDCODED_IPS[hostname]?.let { ips ->
            Log.w(TAG, "Using hardcoded IPs for $hostname: $ips")
            val addresses = ips.mapNotNull { ip ->
                try {
                    val ipBytes = ip.split(".").map { it.toInt().toByte() }.toByteArray()
                    InetAddress.getByAddress(hostname, ipBytes)
                } catch (e: Exception) { null }
            }
            if (addresses.isNotEmpty()) {
                cacheResult(hostname, addresses)
                return addresses
            }
        }

        throw UnknownHostException("Failed to resolve $hostname via all DNS methods")
    }

    /**
     * 并行查询所有公共 DNS 服务器（UDP + DoH），返回第一个成功结果
     *
     * 5G 网络优化：同时向所有 DNS 服务器发起查询，最快的返回决定整体延迟。
     * 这避免了顺序查询时单个慢 DNS 服务器拖慢整体解析的问题。
     *
     * 实现使用线程池并行查询 + CountDownLatch 等待第一个成功结果。
     */
    private fun resolveViaParallelDns(hostname: String, isDomestic: Boolean): List<InetAddress> {
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        val result = java.util.concurrent.CompletableFuture<List<InetAddress>>()
        val latch = java.util.concurrent.CountDownLatch(1)

        // 收集所有查询任务
        val tasks = mutableListOf<Runnable>()

        // UDP 查询任务
        for (dnsServer in PUBLIC_DNS_SERVERS) {
            tasks.add(Runnable {
                try {
                    val addresses = resolveViaUdp(hostname, dnsServer)
                    if (addresses.isNotEmpty()) {
                        Log.d(TAG, "Resolved $hostname via UDP $dnsServer -> ${addresses.map { it.hostAddress }}")
                        result.complete(addresses)
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    // 静默失败，让其他查询继续
                }
            })
        }

        // DoH 查询任务
        for (dohServer in DOH_SERVERS) {
            tasks.add(Runnable {
                try {
                    val addresses = resolveViaDoH(hostname, dohServer)
                    if (addresses.isNotEmpty()) {
                        Log.d(TAG, "Resolved $hostname via DoH ${dohServer.domain} -> ${addresses.map { it.hostAddress }}")
                        result.complete(addresses)
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    // 静默失败
                }
            })
        }

        // 提交所有任务
        tasks.forEach { executor.submit(it) }

        try {
            // 等待第一个成功结果（最长等待 DNS_TIMEOUT_MS）
            latch.await(DNS_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            // 取消所有未完成的任务
            executor.shutdownNow()
            return result.getNow(emptyList())
        } catch (e: Exception) {
            executor.shutdownNow()
            return emptyList()
        }
    }

    /** 判断是否为已知被污染的域名 */
    private fun isPollutedDomain(hostname: String): Boolean {
        val lower = hostname.lowercase()
        return POLLUTED_DOMAINS.any { lower.endsWith(it) || lower.contains(it) }
    }

    /** 判断是否为国内域名（系统 DNS 通常可靠，不过滤私有 IP） */
    private fun isDomesticDomain(hostname: String): Boolean {
        val lower = hostname.lowercase()
        return DOMESTIC_DOMAIN_SUFFIXES.any { lower.endsWith(it) || lower.contains(it) }
    }

    /** 检查 IP 是否有效（过滤明显的错误解析） */
    private fun isValidIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        // 过滤明显的错误 IP
        if (ip == "0.0.0.0" || ip == "127.0.0.1") return false
        // 过滤私有地址（某些 DNS 污染会返回局域网 IP）
        // 注意：仅对国外域名过滤，国内域名的私有 IP 由调用方决定
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return false
        if (ip.startsWith("172.")) {
            val second = ip.substringAfter("172.").substringBefore(".").toIntOrNull() ?: return false
            if (second in 16..31) return false
        }
        return true
    }

    private fun cacheResult(hostname: String, addresses: List<InetAddress>) {
        val expireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CACHE_TTL_SECONDS)
        cache[hostname] = Pair(addresses, expireAt)
    }

    /** 通过 UDP 直连 DNS 服务器查询 */
    private fun resolveViaUdp(hostname: String, dnsServer: String): List<InetAddress> {
        val queryPacket = buildDnsQuery(hostname)
        val socket = DatagramSocket()
        socket.soTimeout = DNS_TIMEOUT_MS
        try {
            val serverAddress = InetAddress.getByName(dnsServer)
            val request = DatagramPacket(queryPacket, queryPacket.size, serverAddress, 53)
            socket.send(request)

            val buffer = ByteArray(1024)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            return parseDnsResponse(response.data, response.length, hostname)
        } finally {
            socket.close()
        }
    }

    /**
     * 通过 DoH (DNS over HTTPS) 查询域名
     *
     * 使用预置 IP 直连 DoH 服务器，避免 DNS 鸡蛋问题：
     * - 阿里 DoH: dns.alidns.com → 223.5.5.5
     * - 腾讯 DoH: doh.pub → 119.29.29.29
     *
     * 响应格式: {"Status":0,"Answer":[{"name":"example.com","type":1,"TTL":300,"data":"1.2.3.4"}]}
     */
    private fun resolveViaDoH(hostname: String, dohServer: DohServer): List<InetAddress> {
        // 逐个尝试预置 IP
        for (ip in dohServer.ips) {
            try {
                val result = resolveViaDoHWithIp(hostname, dohServer, ip)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                Log.w(TAG, "DoH ${dohServer.domain} via $ip failed: ${e.message}")
            }
        }
        return emptyList()
    }

    /** 使用指定 IP 连接 DoH 服务器 */
    private fun resolveViaDoHWithIp(hostname: String, dohServer: DohServer, ip: String): List<InetAddress> {
        // 使用 IP 地址构建 URL，通过 Host header 和 SNI 指定真实域名
        val url = URL("https://$ip${dohServer.path}?name=$hostname&type=A")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = DOH_TIMEOUT_MS
            readTimeout = DOH_TIMEOUT_MS
            setRequestProperty("Accept", "application/dns-json")
            setRequestProperty("Host", dohServer.domain)
            setRequestProperty("User-Agent", "TVLive/1.0")

            // 使用信任所有证书的 SSL 工厂（仅用于 DoH 查询，因为直连 IP 时证书域名不匹配）
            sslSocketFactory = createTrustingSslSocketFactory()
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }

        try {
            val responseCode = conn.responseCode
            if (responseCode != 200) return emptyList()

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            // Status 0 = NOERROR
            if (json.optInt("Status", -1) != 0) return emptyList()

            val answer = json.optJSONArray("Answer") ?: return emptyList()
            val result = mutableListOf<InetAddress>()

            for (i in 0 until answer.length()) {
                val record = answer.getJSONObject(i)
                val type = record.optInt("type")
                val data = record.optString("data")

                // type 1 = A record (IPv4)
                if (type == 1 && data.isNotBlank() && isValidIp(data)) {
                    val ipParts = data.split(".")
                    if (ipParts.size == 4) {
                        val ipBytes = ByteArray(4)
                        var valid = true
                        for (j in 0 until 4) {
                            val b = ipParts[j].toIntOrNull()
                            if (b == null || b !in 0..255) {
                                valid = false
                                break
                            }
                            ipBytes[j] = b.toByte()
                        }
                        if (valid) {
                            result.add(InetAddress.getByAddress(hostname, ipBytes))
                        }
                    }
                }
            }

            return result
        } finally {
            conn.disconnect()
        }
    }

    /** 创建信任所有证书的 SSLSocketFactory（仅用于 DoH 直连 IP） */
    private fun createTrustingSslSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    /** 构造标准 DNS 查询报文（A 记录查询） */
    private fun buildDnsQuery(hostname: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeShort(0xABCD)     // Transaction ID
        dos.writeShort(0x0100)     // Flags: standard query, RD=1
        dos.writeShort(1)          // Question count
        dos.writeShort(0)          // Answer count
        dos.writeShort(0)          // Authority count
        dos.writeShort(0)          // Additional count

        for (label in hostname.split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty()) continue
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)           // QNAME 结束
        dos.writeShort(1)          // QTYPE: A
        dos.writeShort(1)          // QCLASS: IN

        return baos.toByteArray()
    }

    /** 解析 DNS 响应报文 */
    private fun parseDnsResponse(data: ByteArray, length: Int, hostname: String): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        val dis = DataInputStream(ByteArrayInputStream(data, 0, length))

        dis.readShort()                        // Transaction ID
        val flags = dis.readUnsignedShort()
        val rcode = flags and 0x0F
        if (rcode != 0) return emptyList()

        val qdcount = dis.readUnsignedShort()
        val ancount = dis.readUnsignedShort()
        dis.readUnsignedShort()                // nscount
        dis.readUnsignedShort()                // arcount

        for (i in 0 until qdcount) {
            skipName(dis)
            dis.readUnsignedShort()            // QTYPE
            dis.readUnsignedShort()            // QCLASS
        }

        for (i in 0 until ancount) {
            skipName(dis)
            val type = dis.readUnsignedShort()
            dis.readUnsignedShort()            // CLASS
            dis.readInt()                      // TTL
            val rdLength = dis.readUnsignedShort()

            if (type == 1 && rdLength == 4) {
                val ipBytes = ByteArray(4)
                dis.readFully(ipBytes)
                val ipStr = ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                if (isValidIp(ipStr)) {
                    result.add(InetAddress.getByAddress(hostname, ipBytes))
                }
            } else if (type == 5) {
                dis.skipBytes(rdLength)        // CNAME
            } else {
                dis.skipBytes(rdLength)
            }
        }

        return result
    }

    private fun skipName(dis: DataInputStream) {
        while (true) {
            val len = dis.readUnsignedByte()
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                dis.readUnsignedByte()
                break
            }
            dis.skipBytes(len)
        }
    }
}
