package com.tvlive.app.net

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.Builder
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
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
 *
 * **反屏蔽增强（参考电视直播应用）**：
 * - User-Agent 伪装：播放请求伪装桌面浏览器 UA，避免被 CDN/ISP 拒绝
 * - Referer/Origin 注入：部分流媒体 CDN 校验 Referer，缺失则返回 403
 * - 请求头清理：移除可能暴露设备类型的信息头
 */
object HttpClientProvider {

    /** 桌面浏览器 User-Agent（参考电视直播应用的反屏蔽策略） */
    const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 通用 User-Agent（数据请求用） */
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
     * 播放器请求拦截器 - 反屏蔽核心
     *
     * 参考电视直播应用的策略：
     * 1. User-Agent 伪装为桌面浏览器，避免 CDN 拒绝来自 TV/移动设备的请求
     * 2. 注入 Referer 头，部分流媒体 CDN 校验 Referer 白名单
     * 3. 注入 Origin 头，与 Referer 配合通过 CDN 校验
     *
     * 不影响数据请求（M3U 加载），仅应用于播放器请求
     */
    private val playerAntiBlockInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()

        // 构建反屏蔽请求
        val requestBuilder = originalRequest.newBuilder()
            .header("User-Agent", DESKTOP_USER_AGENT)

        // 对流媒体请求注入 Referer（部分 CDN 校验 Referer）
        // 参考 APK 的 ikkHeaders:// 和 kooHeaders:// 协议
        if (url.contains(".m3u8", ignoreCase = true) || url.contains(".m3u", ignoreCase = true) ||
            url.contains("/live/", ignoreCase = true) || url.contains("stream", ignoreCase = true)) {
            val host = originalRequest.url.host
            // 只对已知需要 Referer 的域名注入（避免影响不需要的）
            if (needsReferer(host)) {
                val referer = "${originalRequest.url.scheme}://${host}/"
                requestBuilder.header("Referer", referer)
                requestBuilder.header("Origin", referer.trimEnd('/'))
            }
        }

        // 移除可能暴露设备类型的头
        requestBuilder.removeHeader("X-Requested-With")

        chain.proceed(requestBuilder.build())
    }

    /**
     * 数据请求拦截器 - 为数据请求设置合理的 UA
     */
    private val dataInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val request = originalRequest.newBuilder()
            .header("User-Agent", USER_AGENT)
            .removeHeader("X-Requested-With")
            .build()
        chain.proceed(request)
    }

    /**
     * 判断域名是否需要 Referer 头
     *
     * 部分流媒体 CDN 会校验 Referer 白名单，缺失则返回 403 Forbidden。
     * 这类域名主要是国内广电/CDN 运营商的流媒体服务器。
     */
    private fun needsReferer(host: String): Boolean {
        val lower = host.lowercase()
        return lower.contains("cctv.com") ||
               lower.contains("cctvplus.com") ||
               lower.contains("cntv.cn") ||
               lower.contains("chinamobile.com") ||
               lower.contains("cmcc.cn") ||
               lower.contains("itv.cmcc.cn") ||
               lower.contains("ott.cibntv.net") ||
               lower.contains("pdtvhd.com") ||
               lower.contains("bestv.com.cn") ||
               lower.contains("smgbb.cn") ||
               lower.contains("kankanlive.com") ||
               lower.contains("juyun.tv")
    }

    /**
     * 用于数据请求的 OkHttpClient（加载直播源、EPG 等）
     *
     * 5G 中国移动网络深度优化参数：
     * - 连接超时（2秒）：5G 网络首包延迟 10-30ms，2 秒足够建立 TCP 连接
     *   并行请求 8+ 个镜像时，2 秒足以让所有镜像要么成功要么快速失败
     * - 读取超时（5秒）：M3U 文件较小（60KB 左右），5G 网络下 1 秒即可下载完
     * - 写入超时（3秒）：数据请求通常不写 body
     * - callTimeout（8秒）：单次请求总超时，避免单个慢镜像拖慢整体加载
     *
     * 关键：并行请求模式下，单个镜像的快速失败比慢速成功更重要
     * ChannelRepository.fetchUrl 使用"先成功者胜"语义，最快的镜像决定整体响应时间
     */
    val dataClient: OkHttpClient = Builder()
        .dns(safeDns)
        .addInterceptor(dataInterceptor)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)  // 并行模式下不需要重试，失败立即切换镜像
        .connectionPool(dataConnectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 用于播放器的 OkHttpClient（播放流媒体）
     *
     * 5G 中国移动直播流播放优化参数：
     * - 连接超时（4秒）：5G 网络下首包快（10-30ms），4 秒足够；缩短以快速触发 URL 切换
     * - 读取超时（15秒）：HLS 直播切片间隔 6-10 秒，15 秒能容忍切换抖动
     * - 无 callTimeout：长直播不能因总时间超时而中断
     * - 自动重试：移动网络抖动频繁（单 URL 内部重试，不影响 URL 级切换）
     * - 跟随重定向：CDN 调度需要 302 跳转
     * - 反屏蔽拦截器：伪装桌面 UA + 注入 Referer/Origin
     *
     * 注意：URL 级别的切换由 TvPlayerManager.handlePlayerError 处理，
     * 单 URL 内部重试由 OkHttp + LoadErrorHandlingPolicy 处理。
     */
    val playerClient: OkHttpClient = Builder()
        .dns(safeDns)
        .addInterceptor(playerAntiBlockInterceptor)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(playerConnectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
