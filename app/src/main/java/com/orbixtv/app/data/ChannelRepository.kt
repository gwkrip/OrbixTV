package com.orbixtv.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ChannelRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("orbixtv_prefs", Context.MODE_PRIVATE)
    }

    private val _groups = MutableStateFlow<List<ChannelGroup>>(emptyList())
    val groups: StateFlow<List<ChannelGroup>> = _groups

    private val _favorites = MutableStateFlow<List<Channel>>(emptyList())
    val favorites: StateFlow<List<Channel>> = _favorites

    // --- Playlist URL management ---

    fun getPlaylistUrl(): String = prefs.getString("playlist_url", "") ?: ""

    fun savePlaylistUrl(url: String) {
        prefs.edit().putString("playlist_url", url.trim()).apply()
    }

    fun clearPlaylistUrl() {
        prefs.edit().remove("playlist_url").apply()
    }

    // --- Load playlist ---

    /**
     * Load dari URL eksternal jika tersedia, fallback ke assets.
     * Return null jika sukses, atau pesan error jika gagal.
     */
    suspend fun loadPlaylist(): String? {
        val url = getPlaylistUrl()
        return if (url.isNotEmpty()) {
            val result = M3uParser.parseFromUrl(url)
            if (result.isSuccess) {
                val groups = result.getOrNull() ?: emptyList()
                _groups.value = groups
                loadFavorites(groups.flatMap { it.channels })
                null
            } else {
                // Gagal dari URL → fallback ke assets
                loadFromAssets()
                "Gagal memuat dari URL: ${result.exceptionOrNull()?.message ?: "Error tidak diketahui"}"
            }
        } else {
            loadFromAssets()
            null
        }
    }

    private suspend fun loadFromAssets() {
        val groups = M3uParser.parseFromAssets(context)
        _groups.value = groups
        loadFavorites(groups.flatMap { it.channels })
    }

    fun getAllChannels(): List<Channel> = _groups.value.flatMap { it.channels }

    fun searchChannels(query: String): List<Channel> {
        return getAllChannels().filter { channel ->
            channel.name.contains(query, ignoreCase = true) ||
                    channel.group.contains(query, ignoreCase = true)
        }
    }

    // --- Favorites (menggunakan String ID) ---

    fun toggleFavorite(channelId: String) {
        val favIds = getFavoriteIds().toMutableSet()
        if (favIds.contains(channelId)) {
            favIds.remove(channelId)
        } else {
            favIds.add(channelId)
        }
        prefs.edit().putStringSet("favorites", favIds).apply()
        loadFavorites(getAllChannels())
    }

    fun isFavorite(channelId: String): Boolean = getFavoriteIds().contains(channelId)

    private fun getFavoriteIds(): Set<String> =
        prefs.getStringSet("favorites", emptySet()) ?: emptySet()

    private fun loadFavorites(allChannels: List<Channel>) {
        val favIds = getFavoriteIds()
        _favorites.value = allChannels
            .filter { favIds.contains(it.id) }
            .map { it.copy(isFavorite = true) }
    }

    fun enrichWithFavorite(channel: Channel): Channel =
        channel.copy(isFavorite = isFavorite(channel.id))

    // --- Recently watched (menggunakan String ID) ---

    fun getLastWatched(): List<String> {
        val raw = prefs.getString("last_watched", "") ?: ""
        return if (raw.isEmpty()) emptyList()
        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun addToLastWatched(channelId: String) {
        val current = getLastWatched().toMutableList()
        current.remove(channelId)
        current.add(0, channelId)
        val limited = current.take(30)  // Naik dari 20 → 30
        prefs.edit().putString("last_watched", limited.joinToString(",")).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("last_watched").apply()
    }

    // --- Sleep timer ---

    fun saveSleepTimer(minutes: Int) {
        prefs.edit().putInt("sleep_timer_minutes", minutes).apply()
    }

    fun getSleepTimer(): Int = prefs.getInt("sleep_timer_minutes", 0)
}
