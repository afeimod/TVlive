package com.tvlive.app

import android.app.Application
import com.tvlive.app.data.ChannelRepository
import com.tvlive.app.net.ISPDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TvLiveApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var repository: ChannelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = ChannelRepository(this)

        appScope.launch {
            // 并行初始化：ISP 检测 + 默认源加载
            // ISP 检测结果会影响后续的 URL 优先级和反屏蔽策略
            launch {
                try {
                    ISPDetector.detect(this@TvLiveApp)
                } catch (e: Exception) {
                    // ISP 检测失败不应阻塞应用启动
                    android.util.Log.w("TvLiveApp", "ISP detection failed: ${e.message}")
                }
            }
            launch {
                repository.initDefaultSources()
            }
        }
    }

    companion object {
        lateinit var instance: TvLiveApp
            private set
    }
}
