package com.orbixtv.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orbixtv.app.data.Channel
import com.orbixtv.app.data.ChannelGroup
import com.orbixtv.app.data.ChannelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repository = ChannelRepository(application)

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _searchResults = MutableStateFlow<List<Channel>>(emptyList())
    val searchResults: StateFlow<List<Channel>> = _searchResults

    val groups: StateFlow<List<ChannelGroup>> = repository.groups
    val favorites: StateFlow<List<Channel>> = repository.favorites

    init {
        loadPlaylist()
    }

    private fun loadPlaylist() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.loadPlaylist()
            _isLoading.value = false
        }
    }

    fun search(query: String) {
        _searchResults.value = if (query.isBlank()) emptyList()
        else repository.searchChannels(query)
    }

    fun toggleFavorite(channelId: Int) {
        repository.toggleFavorite(channelId)
    }

    fun isFavorite(channelId: Int): Boolean = repository.isFavorite(channelId)

    fun getRecentChannels(): List<Channel> {
        val ids = repository.getLastWatched()
        val allChannels = repository.getAllChannels()
        return ids.mapNotNull { id -> allChannels.firstOrNull { it.id == id } }
    }

    fun onChannelWatched(channelId: Int) {
        repository.addToLastWatched(channelId)
    }
}
