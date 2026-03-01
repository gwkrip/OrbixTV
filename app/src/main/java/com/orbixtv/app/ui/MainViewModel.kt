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

    private val repository = ChannelRepository(application)

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    private val _searchResults = MutableStateFlow<List<Channel>>(emptyList())
    val searchResults: StateFlow<List<Channel>> = _searchResults

    val groups: StateFlow<List<ChannelGroup>> = repository.groups
    val favorites: StateFlow<List<Channel>> = repository.favorites

    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes: StateFlow<Int> = _sleepTimerMinutes

    init {
        loadPlaylist()
    }

    fun loadPlaylist() {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            val error = repository.loadPlaylist()
            _loadError.value = error
            _isLoading.value = false
        }
    }

    // --- Playlist URL management ---

    fun getPlaylistUrl(): String = repository.getPlaylistUrl()

    fun setPlaylistUrl(url: String) {
        repository.savePlaylistUrl(url)
        loadPlaylist()
    }

    fun resetToDefaultPlaylist() {
        repository.clearPlaylistUrl()
        loadPlaylist()
    }

    // --- Search ---

    fun search(query: String) {
        _searchResults.value = if (query.isBlank()) emptyList()
        else repository.searchChannels(query)
    }

    // --- Favorites ---

    // #9: toggleFavorite sekarang suspend — dipanggil lewat viewModelScope
    fun toggleFavorite(channelId: String) {
        viewModelScope.launch {
            repository.toggleFavorite(channelId)
        }
    }

    fun isFavorite(channelId: String): Boolean = repository.isFavorite(channelId)

    // --- Recent / History ---

    fun getAllChannels(): List<Channel> = repository.getAllChannels()

    // #1: delegate ke repository.getRecentChannels() yang sudah pakai Map O(1)
    fun getRecentChannels(): List<Channel> = repository.getRecentChannels()

    fun onChannelWatched(channelId: String) {
        repository.addToLastWatched(channelId)
    }

    fun clearHistory() {
        repository.clearHistory()
    }

    // --- Sleep Timer ---

    fun setSleepTimer(minutes: Int) {
        _sleepTimerMinutes.value = minutes
        repository.saveSleepTimer(minutes)
    }

    fun clearSleepTimer() {
        _sleepTimerMinutes.value = 0
    }
}
