package com.tvlive.app.data

import com.tvlive.app.data.model.Source

/**
 * 默认内置直播源; - 均为公开免费源
 *
 * v7 变更（参考电视直播APK反编译分析）：
 * - 新增 APK 直播源服务器（fengcaizb.com）作为首选源
 *   该源提供 ISP 差异化 CDN（$Y移动/$D电信/$L联通），在中国移动网络下可直接播放
 * - 保留 iptv-org 作为备用源
 * - 内置备用频道（fallback_channels.m3u）从 APK 的 1769 频道中提取了 1296 个可直接播放的频道
 *   包含 363 个中国移动 CDN 标记的频道（$Y），优先使用移动 CDN 地址
 *
 * 反屏蔽策略（从 APK 分析得出）：
 * 1. AES 加密 URL → 防 DPI 深度包检测识别和封锁
 * 2. ISP 标签路由 → $Y(移动)/$D(电信)/$L(联通) 使用对应运营商 CDN
 * 3. User-Agent 伪装 → 伪装桌面浏览器 UA 绕过设备类型过滤
 * 4. 自定义 Headers → ikkHeaders:// 协议注入 Referer/Origin 绕过 CDN 校验
 * 5. DNS 缓存清理 → dns_cache_clear=1 防止 DNS 污染
 * 6. 自动重连 → reconnect=3 在断连时自动恢复
 * 7. IPv6 回退 → $i6 标签使用 IPv6 路径绕过 IPv4 封锁
 * 8. 多 CDN 源 → 咪咕/腾讯/抖音/CCTV/移动IPTV 多源自动降级
 */
object DefaultSources {

    /** 源版本号 - 版本变化时会重新初始化默认源 */
    const val VERSION = 7

    val sources = listOf(
        // ==================== 首选源：APK 直播源服务器 ====================
        // 电视直播APK的数据服务器，提供ISP差异化CDN的加密频道数据
        // 该服务器返回gzip压缩的JSON，URL经AES-128-CBC加密防止DPI检测
        // 加载后ISPDetector自动识别运营商，UrlHelper按ISP标签路由到最优CDN
        Source(
            name = "电视直播源(反屏蔽)",
            url = "http://ds.fengcaizb.com/channels/dszb3.gz",
            isDefault = true,
            enabled = true
        ),
        // ==================== 备用源：iptv-org ====================
        // GitHub Pages 域名，UrlHelper 自动生成 7+ 个国内可直连镜像
        Source(
            name = "iptv-org 中国频道",
            url = "https://iptv-org.github.io/iptv/countries/cn.m3u",
            isDefault = false,
            enabled = true
        )
    )

    /** APK AES 解密密钥（从 jadx 反编译 libjerry.so JNI 提取） */
    const val AES_KEY = "you!je@19rr$20y#"
    /** APK AES IV */
    const val AES_IV_BYTES = byteArrayOf(65, 114, 101, 121, 111, 117, 124, 62, 127, 110, 54, 38, 13, 97, 110, 63)

    /** EPG 节目单地址（GitHub URL，UrlHelper自动生成镜像） */
    const val EPG_URL = "https://iptv-org.github.io/iptv/guides/cn.xml"
}
