package com.tvlive.app.net

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.Builder
import java.util.concurrent.TimeUnit

/**
 * 统一 OkHttp 客户端工厂
 *
 * 为整个应用提供预配置的 OkHttpClient，针对中国移动 5G/4G 网络深度优化：
 *
 * **5G 网络特性适配**：
 * - 5G 网络延迟低（10-30ms）、带宽高，但移动网络存在切换抖动
 * - 短超时 + 并行请求模式：单个镜像快速失败，并行请求多个镜像取最快者
 * - 连接池加大：并行请求 7+ 个镜像需要更多并发连接
 *
 * **中国移动网络专项优化**：
 * - 自定义 DNS（SafeDns）：绕过运营商 DNS 污染，支持 DoH 并发查询
 * - 自动重试：网络抖动时自动重试
 * - 跟随重定向：CDN 调度需要 302 跳转
 * - HTTP/2：多路复用降低延迟，特别是 HLS 切片拉取
 * - Keep-Alive：复用 TCP 连接，减少握手开销
 */
object HttpClientProvider {

    /** 通用 User-Agent，避免部分服务器拒绝无 UA 的请求 */
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) TVLive/1.0"

    /** DNS 解析器单例（带缓存 + DoH 并发查询） */
    private val safeDns = SafeDns()

    /**
     * 数据请求连接池（5G 优化版）
     * - 最大空闲连接数：20（并行请求 7+ 个镜像 + 余量）
     * - 保持时间：3 分钟（避免频繁重建连接）
     */
    private val dataConnectionPool = ConnectionPool(20, 3, TimeUnit.MINUTES)

    /**
     * 播放器连接池（独立，避免与数据请求互相影响）
     * - 最大空闲连接数：5（单个频道通常 1-2 个连接）
     * - 保持时间：5 分钟
     */
    private val playerConnectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    /**
     * 用于数据请求的 OkHttpClient（加载直播源、EPG 等）
     *
     * 5G 网络优化参数：
     * - 连接超时（3秒）：5G 网络首包延迟低，3 秒足够；并行请求模式下短超时可快速失败
     * - 读取超时（8秒）：M3U 文件较小，8 秒足够完成下载
     * - 写入超时（5秒）：数据请求通常不写 body
     * - callTimeout（15秒）：单次请求总超时，避免极端情况下卡死
     *
     * 注：ChannelRepository.fetchUrl 使用并行请求模式，会同时发起 7+ 个镜像请求，
     * 短超时可让慢镜像快速失败，最快的镜像决定整体响应时间。
     */
    val dataClient: OkHttpClient = Builder()
        .dns(safeDns)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(dataConnectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 用于播放器的 OkHttpClient（播放流媒体）
     *
     * 5G 直播流播放优化参数：
     * - 连接超时（8秒）：5G 网络下首包快，但移动网络切换时可能短暂中断
     *   8 秒平衡了快速失败和容忍切换抖动
     * - 读取超时（20秒）：HLS 直播切片可能间隔较长（6-10秒/片），避免读超时
     * - 无 callTimeout：长直播不能因总时间超时而中断
     * - 自动重试：移动网络抖动频繁
     * - 跟随重定向：CDN 调度需要 302 跳转
     *
     * 注意：实际播放 URL 切换由 TvPlayerManager 的多 URL 降级机制处理，
     * 这里只是 OkHttp 层面的单 URL 重试。
     */
    val playerClient: OkHttpClient = Builder()
        .dns(safeDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(playerConnectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
