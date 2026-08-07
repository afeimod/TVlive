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
 * - 启用 HTTP/2：多路复用降低延迟，特别是 HLS 切片拉取
 * - 允许重定向：许多 CDN 需要跟随 302 跳转
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
     * - 短连接超时（5秒）：让失败的 URL 快速切换到下一个镜像
     *   中国移动网络下可能有 9+ 个镜像 URL 需要尝试，5秒超时确保总时间可控
     * - 较长读取超时（20秒）：适配大文件下载
     * - 自定义 DNS + 连接失败自动重试
     * - 启用 HTTP/2（默认支持，OkHttp 自动协商）
     */
    val dataClient: OkHttpClient = Builder()
        .dns(safeDns)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(connectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 用于播放器的 OkHttpClient（播放流媒体）
     *
     * 中国移动网络下直播流播放的关键调优：
     * - 较长连接超时（15秒）：移动网络首包延迟较高，避免误判
     * - 较长读取超时（30秒）：HLS 直播切片可能间隔较长，避免读超时
     *   直播 m3u8 切片列表会定期刷新，单次请求可能等待新切片
     * - 无 callTimeout 限制：长直播不能因总时间超时而中断
     * - 自动重试：移动网络抖动频繁
     * - 跟随重定向：CDN 调度需要 302 跳转
     *
     * 注意：实际播放 URL 切换由 TvPlayerManager 的多 URL 降级机制处理，
     * 这里只是 OkHttp 层面的单 URL 重试。
     */
    val playerClient: OkHttpClient = Builder()
        .dns(safeDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(connectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
