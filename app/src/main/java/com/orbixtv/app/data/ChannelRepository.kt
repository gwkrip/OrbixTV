package com.orbixtv.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class ChannelRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("orbixtv_prefs", Context.MODE_PRIVATE)
    }

    private val _groups = MutableStateFlow<List<ChannelGroup>>(emptyList())
    val groups: StateFlow<List<ChannelGroup>> = _groups

    private val _favorites = MutableStateFlow<List<Channel>>(emptyList())
    val favorites: StateFlow<List<Channel>> = _favorites

    // #2: Cache hasil flatMap — tidak alokasi List baru setiap getAllChannels() dipanggil
    private var cachedAllChannels: List<Channel> = emptyList()

    // #3: Cache favoriteId di memory — tidak baca SharedPreferences setiap isFavorite() dipanggil
    private var cachedFavoriteIds: Set<String> = emptySet()

    // #1: Map id→Channel untuk lookup O(1) di getRecentChannels()
    private var channelById: Map<String, Channel> = emptyMap()

    // --- Playlist URL management ---

    fun getPlaylistUrl(): String = prefs.getString("playlist_url", "") ?: ""

    fun savePlaylistUrl(url: String) {
        prefs.edit().putString("playlist_url", url.trim()).apply()
    }

    fun clearPlaylistUrl() {
        prefs.edit().remove("playlist_url").apply()
    }

    // --- Load playlist ---

    suspend fun loadPlaylist(): String? {
        val url = getPlaylistUrl()
        return if (url.isNotEmpty()) {
            val result = M3uParser.parseFromUrl(url)
            if (result.isSuccess) {
                val groups = result.getOrNull() ?: emptyList()
                applyGroups(groups)
                null
            } else {
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
        applyGroups(groups)
    }

    /** Update semua cache sekaligus setelah data baru masuk */
    private fun applyGroups(groups: List<ChannelGroup>) {
        _groups.value = groups
        // #2: Rebuild cache sekali, bukan setiap panggil getAllChannels()
        cachedAllChannels = groups.flatMap { it.channels }
        // #1: Rebuild lookup map untuk O(1) recent lookup
        channelById = cachedAllChannels.associateBy { it.id }
        // Refresh favorites dengan data channel terbaru
        refreshFavoritesCache()
    }

    // #2: Langsung kembalikan cache — tidak alokasi ulang
    fun getAllChannels(): List<Channel> = cachedAllChannels

    fun searchChannels(query: String): List<Channel> {
        val q = query.lowercase()
        return cachedAllChannels.filter { channel ->
            channel.name.lowercase().contains(q) ||
                    channel.group.lowercase().contains(q)
        }
    }

    // --- Favorites ---

    // #9: suspend + Dispatchers.IO agar tidak blokir main thread
    suspend fun toggleFavorite(channelId: String) = withContext(Dispatchers.IO) {
        val favIds = cachedFavoriteIds.toMutableSet()
        if (favIds.contains(channelId)) {
            favIds.remove(channelId)
        } else {
            favIds.add(channelId)
        }
        prefs.edit().putStringSet("favorites", favIds).apply()
        // Update cache synchronously (sudah di IO thread)
        cachedFavoriteIds = favIds
        // Emit ke StateFlow harus di-dispatch ke main thread konteks
        refreshFavoritesCache()
    }

    // #3: Baca dari cache, bukan SharedPreferences
    fun isFavorite(channelId: String): Boolean = cachedFavoriteIds.contains(channelId)

    private fun loadFavoriteIds(): Set<String> =
        prefs.getStringSet("favorites", emptySet()) ?: emptySet()

    private fun refreshFavoritesCache() {
        // Pastikan cache favoriteIds sudah diisi
        if (cachedFavoriteIds.isEmpty()) {
            cachedFavoriteIds = loadFavoriteIds()
        }
        val favIds = cachedFavoriteIds
        _favorites.value = cachedAllChannels
            .filter { favIds.contains(it.id) }
            .map { it.copy(isFavorite = true) }
    }

    fun enrichWithFavorite(channel: Channel): Channel =
        channel.copy(isFavorite = isFavorite(channel.id))

    // --- Recently watched ---

    fun getLastWatched(): List<String> {
        val raw = prefs.getString("last_watched", "") ?: ""
        return if (raw.isEmpty()) emptyList()
        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // #1: Lookup O(1) via Map, tidak scan linear List
    fun getRecentChannels(): List<Channel> {
        return getLastWatched().mapNotNull { id -> channelById[id] }
    }

    fun addToLastWatched(channelId: String) {
        val current = getLastWatched().toMutableList()
        current.remove(channelId)
        current.add(0, channelId)
        val limited = current.take(30)
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
