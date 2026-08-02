package com.tvlive.app.net

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.Builder
import java.util.concurrent.TimeUnit

/**
 * 统一 OkHttp 客户端工厂
 *
 * 为整个应用提供预配置的 OkHttpClient，解决中国移动网络下的连接问题：
 * - 自定义 DNS（SafeDns）：绕过运营商 DNS 污染
 * - 合理超时设置：避免长时间卡在不可达的连接上
 * - 连接重试：网络抖动时自动重试
 * - 连接池复用：提高后续请求效率
 */
object HttpClientProvider {

    /** 通用 User-Agent，避免部分服务器拒绝无 UA 的请求 */
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) TVLive/1.0"

    /** DNS 解析器单例（带缓存） */
    private val safeDns = SafeDns()

    /** 连接池（默认: 5个空闲连接, 5分钟存活时间） */
    private val connectionPool = ConnectionPool()

    /**
     * 用于数据请求的 OkHttpClient（加载直播源、EPG 等）
     * - 较长的超时时间（适配大文件下载）
     * - 自定义 DNS
     * - 连接失败自动重试
     */
    val dataClient: OkHttpClient = Builder()
        .dns(safeDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(connectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 用于播放器的 OkHttpClient（播放流媒体）
     * - 较短的超时时间（快速失败，便于切换源）
     * - 自定义 DNS
     * - 连接失败自动重试
     */
    val playerClient: OkHttpClient = Builder()
        .dns(safeDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(connectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
