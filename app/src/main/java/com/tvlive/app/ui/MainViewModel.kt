package com.tvlive.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tvlive.app.TvLiveApp
import com.tvlive.app.data.ChannelRepository
import com.tvlive.app.data.RefreshResult
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.ChannelGroup
import com.tvlive.app.data.model.PlayHistory
import com.tvlive.app.data.model.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as TvLiveApp).repository

    val allChannels: Flow<List<Channel>> = repository.allChannels
    val favorites: Flow<List<Channel>> = repository.favorites
    val allSources: Flow<List<Source>> = repository.allSources
    val groups: Flow<List<String>> = repository.groups
    val history: Flow<List<PlayHistory>> = repository.history

    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    private val _groupedChannels = MutableStateFlow<List<ChannelGroup>>(emptyList())
    val groupedChannels: StateFlow<List<ChannelGroup>> = _groupedChannels.asStateFlow()

    private val _channelCount = MutableStateFlow(0)
    val channelCount: StateFlow<Int> = _channelCount.asStateFlow()

    init {
        loadGroupedChannels()
    }

    fun loadGroupedChannels() {
        viewModelScope.launch {
            repository.getGroupedChannels().collect { groups ->
                _groupedChannels.value = groups
            }
        }
    }

    fun refreshSources() {
        viewModelScope.launch {
            _refreshState.value = RefreshState.Loading(0, 0, "准备中...")
            val result = repository.refreshAllSources { current, total, name ->
                _refreshState.value = RefreshState.Loading(current, total, name)
            }
            _refreshState.value = RefreshState.Done(result)
            _channelCount.value = repository.getChannelCount()
        }
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            repository.toggleFavorite(channel)
        }
    }

    fun addSource(name: String, url: String) {
        viewModelScope.launch {
            repository.addSource(name, url)
            refreshSources()
        }
    }

    fun deleteSource(source: Source) {
        viewModelScope.launch {
            repository.deleteSource(source)
        }
    }

    fun updateSource(source: Source) {
        viewModelScope.launch {
            repository.updateSource(source)
        }
    }

    fun setDefaultSource(id: Long) {
        viewModelScope.launch {
            repository.setDefaultSource(id)
        }
    }

    fun addHistory(channel: Channel) {
        viewModelScope.launch {
            repository.addHistory(channel)
        }
    }

    fun searchChannels(keyword: String): Flow<List<Channel>> = repository.searchChannels(keyword)

    companion object {
        val Factory = ViewModelProvider.AndroidViewModelFactory.getInstance(TvLiveApp.instance)
    }
}

sealed class RefreshState {
    object Idle : RefreshState()
    data class Loading(val current: Int, val total: Int, val sourceName: String) : RefreshState()
    data class Done(val result: RefreshResult) : RefreshState()
}
