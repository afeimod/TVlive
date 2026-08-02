package com.tvlive.app

import android.app.Application
import com.tvlive.app.data.ChannelRepository
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
            repository.initDefaultSources()
        }
    }

    companion object {
        lateinit var instance: TvLiveApp
            private set
    }
}
