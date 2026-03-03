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
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChannelRepository.getInstance(application)

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    private val _searchResults = MutableStateFlow<List<Channel>>(emptyList())
    val searchResults: StateFlow<List<Channel>> = _searchResults

    val groups: StateFlow<List<ChannelGroup>> = repository.groups
    val favorites: StateFlow<List<Channel>> = repository.favorites

    private val _sortOrder = MutableStateFlow(SortOrder.DEFAULT)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private val _streamFilter = MutableStateFlow(StreamFilter.ALL)
    val streamFilter: StateFlow<StreamFilter> = _streamFilter

    init { loadPlaylist() }

    /** Force reload dari URL — dipanggil saat app buka atau URL berubah */
    fun loadPlaylist() {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            _loadError.value = repository.reloadPlaylist()
            _isLoading.value = false
        }
    }

    fun getPlaylistUrl(): String = repository.getPlaylistUrl()
    fun isUsingDefaultUrl(): Boolean = repository.isUsingDefaultUrl()
    fun getDefaultPlaylistUrl(): String = ChannelRepository.DEFAULT_PLAYLIST_URL

    fun saveUrl(url: String) = repository.savePlaylistUrl(url)
    fun resetUrl() = repository.resetToDefaultUrl()

    fun search(query: String) {
        _searchResults.value = if (query.isBlank()) emptyList()
        else repository.searchChannels(query)
    }

    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }
    fun setStreamFilter(filter: StreamFilter) { _streamFilter.value = filter }

    fun getFilteredSortedChannels(source: List<Channel>? = null): List<Channel> =
        repository.getSortedFiltered(
            _sortOrder.value,
            _streamFilter.value,
            source ?: repository.getAllChannels()
        )

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { repository.toggleFavorite(channelId) }
    }
    fun isFavorite(channelId: String): Boolean = repository.isFavorite(channelId)

    fun exportFavorites(onResult: (File?) -> Unit) {
        viewModelScope.launch { onResult(repository.exportFavorites()) }
    }

    fun importFavorites(file: File, onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(repository.importFavorites(file)) }
    }

    fun getAllChannels(): List<Channel> = repository.getAllChannels()
    fun getRecentChannels(): List<Channel> = repository.getRecentChannels()
    fun onChannelWatched(channelId: String) = repository.addToLastWatched(channelId)
    fun clearHistory() = repository.clearHistory()

    fun setSleepTimer(minutes: Int) = repository.saveSleepTimer(minutes)
    fun clearSleepTimer() = repository.clearSleepTimer()
}
