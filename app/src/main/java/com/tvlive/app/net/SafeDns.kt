package com.tvlive.app.net

import android.util.Log
import okhttp3.Dns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 自定义 DNS 解析器
 *
 * 解决中国移动网络 DNS 污染/劫持问题：
 * 移动网络的 DNS 服务器会对 raw.githubusercontent.com、github.io 等
 * 域名返回错误 IP，导致连接失败。联通网络通常不受影响。
 *
 * 策略：
 * 1. 先尝试系统 DNS（对正常域名快速返回）
 * 2. 系统 DNS 失败时，通过 UDP 直连公共 DNS 服务器（阿里 DNS、腾讯 DNS 等）
 *    绕过运营商的 DNS 污染
 * 3. 缓存解析结果，减少重复查询
 */
class SafeDns : Dns {

    companion object {
        private const val TAG = "SafeDns"

        /** 公共 DNS 服务器列表（国内可用的可靠 DNS） */
        private val PUBLIC_DNS_SERVERS = arrayOf(
            "223.5.5.5",      // 阿里 DNS
            "119.29.29.29",   // 腾讯 DNS
            "223.6.6.6",      // 阿里 DNS 备用
            "114.114.114.114"  // 114DNS
        )

        /** DNS 查询超时时间（毫秒） */
        private const val DNS_TIMEOUT_MS = 3000

        /** DNS 缓存有效期（秒） */
        private const val CACHE_TTL_SECONDS = 300L
    }

    /** 域名 -> (IP列表, 过期时间) 缓存 */
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. 检查缓存
        cache[hostname]?.let { (addresses, expireAt) ->
            if (System.currentTimeMillis() < expireAt && addresses.isNotEmpty()) {
                Log.d(TAG, "DNS cache hit: $hostname -> ${addresses.map { it.hostAddress }}")
                return addresses
            }
        }

        // 2. 先尝试系统 DNS（对非污染域名快速返回）
        try {
            val systemResult = Dns.SYSTEM.lookup(hostname)
            if (systemResult.isNotEmpty()) {
                // 过滤掉明显的错误解析结果（如 0.0.0.0）
                val valid = systemResult.filter { !it.hostAddress.equals("0.0.0.0") }
                if (valid.isNotEmpty()) {
                    cacheResult(hostname, valid)
                    return valid
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "System DNS failed for $hostname: ${e.message}")
        }

        // 3. 系统 DNS 失败，通过 UDP 直连公共 DNS 服务器
        val errors = mutableListOf<String>()
        for (dnsServer in PUBLIC_DNS_SERVERS) {
            try {
                val addresses = resolveViaUdp(hostname, dnsServer)
                if (addresses.isNotEmpty()) {
                    Log.d(TAG, "Resolved $hostname via $dnsServer -> ${addresses.map { it.hostAddress }}")
                    cacheResult(hostname, addresses)
                    return addresses
                }
            } catch (e: Exception) {
                errors.add("$dnsServer: ${e.message}")
                Log.w(TAG, "DNS query to $dnsServer failed: ${e.message}")
            }
        }

        throw UnknownHostException("Failed to resolve $hostname. System DNS and all public DNS servers failed: $errors")
    }

    /** 缓存解析结果 */
    private fun cacheResult(hostname: String, addresses: List<InetAddress>) {
        val expireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CACHE_TTL_SECONDS)
        cache[hostname] = Pair(addresses, expireAt)
    }

    /**
     * 通过 UDP 直连指定 DNS 服务器进行域名解析
     * 完全绕过系统 DNS，避免运营商 DNS 污染
     */
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

    /** 构造标准 DNS 查询报文（A 记录查询） */
    private fun buildDnsQuery(hostname: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Transaction ID
        dos.writeShort(0xABCD)
        // Flags: 标准查询, 递归请求 (RD=1)
        dos.writeShort(0x0100)
        // Question count
        dos.writeShort(1)
        // Answer count
        dos.writeShort(0)
        // Authority count
        dos.writeShort(0)
        // Additional count
        dos.writeShort(0)

        // QNAME: 按点分割域名，每段前加长度字节
        for (label in hostname.split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty()) continue
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0) // QNAME 结束

        // QTYPE: A (1)
        dos.writeShort(1)
        // QCLASS: IN (1)
        dos.writeShort(1)

        return baos.toByteArray()
    }

    /** 解析 DNS 响应报文，提取 A 记录 IP 地址 */
    private fun parseDnsResponse(data: ByteArray, length: Int, hostname: String): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        val dis = DataInputStream(ByteArrayInputStream(data, 0, length))

        // 跳过 Transaction ID
        dis.readShort()
        // 读取 Flags
        val flags = dis.readUnsignedShort()
        val rcode = flags and 0x0F
        if (rcode != 0) return emptyList() // DNS 返回错误

        // 读取各段计数
        val qdcount = dis.readUnsignedShort()
        val ancount = dis.readUnsignedShort()
        dis.readUnsignedShort() // nscount
        dis.readUnsignedShort() // arcount

        // 跳过 Question 段
        for (i in 0 until qdcount) {
            skipName(dis)
            dis.readUnsignedShort() // QTYPE
            dis.readUnsignedShort() // QCLASS
        }

        // 解析 Answer 段
        for (i in 0 until ancount) {
            skipName(dis)
            val type = dis.readUnsignedShort()
            dis.readUnsignedShort() // CLASS
            dis.readInt()           // TTL
            val rdLength = dis.readUnsignedShort()

            if (type == 1 && rdLength == 4) {
                // A 记录：4 字节 IPv4 地址
                val ipBytes = ByteArray(4)
                dis.readFully(ipBytes)
                result.add(InetAddress.getByAddress(hostname, ipBytes))
            } else if (type == 5) {
                // CNAME 记录：跳过
                dis.skipBytes(rdLength)
            } else {
                // 其他类型：跳过
                dis.skipBytes(rdLength)
            }
        }

        return result
    }

    /** 跳过 DNS 报文中的域名（可能包含压缩指针） */
    private fun skipName(dis: DataInputStream) {
        while (true) {
            val len = dis.readUnsignedByte()
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                // 压缩指针：读取第二个字节后结束
                dis.readUnsignedByte()
                break
            }
            dis.skipBytes(len)
        }
    }
}
