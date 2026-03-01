package com.orbixtv.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orbixtv.app.data.Channel
import com.orbixtv.app.data.ChannelGroup
import com.orbixtv.app.data.ChannelRepository
import com.orbixtv.app.data.SortOrder
import com.orbixtv.app.data.StreamFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.io.File

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

    // ⑦ Sort & Filter state
    private val _sortOrder = MutableStateFlow(SortOrder.DEFAULT)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private val _streamFilter = MutableStateFlow(StreamFilter.ALL)
    val streamFilter: StateFlow<StreamFilter> = _streamFilter

    init {
        loadPlaylist()
    }

    fun loadPlaylist() {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            _loadError.value = repository.loadPlaylist()
            _isLoading.value = false
        }
    }

    // --- Playlist URL ---
    fun getPlaylistUrl(): String = repository.getPlaylistUrl()
    fun setPlaylistUrl(url: String) { repository.savePlaylistUrl(url); loadPlaylist() }
    fun resetToDefaultPlaylist() { repository.clearPlaylistUrl(); loadPlaylist() }

    // --- Search ---
    fun search(query: String) {
        _searchResults.value = if (query.isBlank()) emptyList()
        else repository.searchChannels(query)
    }

    // --- Sort & Filter ---
    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }
    fun setStreamFilter(filter: StreamFilter) { _streamFilter.value = filter }

    fun getFilteredSortedChannels(source: List<Channel>? = null): List<Channel> =
        repository.getSortedFiltered(
            _sortOrder.value,
            _streamFilter.value,
            source ?: repository.getAllChannels()
        )

    // --- Favorites ---
    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { repository.toggleFavorite(channelId) }
    }
    fun isFavorite(channelId: String): Boolean = repository.isFavorite(channelId)

    // ⑪ Export / Import favorit
    fun exportFavorites(onResult: (File?) -> Unit) {
        viewModelScope.launch {
            onResult(repository.exportFavorites())
        }
    }

    fun importFavorites(file: File, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            onResult(repository.importFavorites(file))
        }
    }

    // --- Recent ---
    fun getAllChannels(): List<Channel> = repository.getAllChannels()
    fun getRecentChannels(): List<Channel> = repository.getRecentChannels()
    fun onChannelWatched(channelId: String) = repository.addToLastWatched(channelId)
    fun clearHistory() = repository.clearHistory()

    // --- Sleep Timer ---
    fun setSleepTimer(minutes: Int) = repository.saveSleepTimer(minutes)
    fun clearSleepTimer() {}
}
