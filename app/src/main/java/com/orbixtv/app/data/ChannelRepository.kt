package com.orbixtv.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChannelRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("orbixtv_prefs", Context.MODE_PRIVATE)
    }

    private val _groups = MutableStateFlow<List<ChannelGroup>>(emptyList())
    val groups: StateFlow<List<ChannelGroup>> = _groups

    private val _favorites = MutableStateFlow<List<Channel>>(emptyList())
    val favorites: StateFlow<List<Channel>> = _favorites

    private var cachedAllChannels: List<Channel> = emptyList()
    private var cachedFavoriteIds: Set<String> = emptySet()
    private var channelById: Map<String, Channel> = emptyMap()

    // --- Playlist URL ---

    fun getPlaylistUrl(): String = prefs.getString("playlist_url", "") ?: ""
    fun savePlaylistUrl(url: String) = prefs.edit().putString("playlist_url", url.trim()).apply()
    fun clearPlaylistUrl() = prefs.edit().remove("playlist_url").apply()

    // --- Load playlist ---

    suspend fun loadPlaylist(): String? {
        val url = getPlaylistUrl()
        return if (url.isNotEmpty()) {
            val result = M3uParser.parseFromUrl(url)
            if (result.isSuccess) {
                applyGroups(result.getOrNull() ?: emptyList())
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

    /** Cek apakah playlist URL masih bisa diakses — untuk WorkManager check */
    suspend fun isPlaylistUrlReachable(): Boolean = withContext(Dispatchers.IO) {
        val url = getPlaylistUrl().ifEmpty { return@withContext true }
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.connect()
            val ok = conn.responseCode in 200..399
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun loadFromAssets() {
        applyGroups(M3uParser.parseFromAssets(context))
    }

    private fun applyGroups(groups: List<ChannelGroup>) {
        _groups.value = groups
        cachedAllChannels = groups.flatMap { it.channels }
        channelById = cachedAllChannels.associateBy { it.id }
        refreshFavoritesCache()
    }

    fun getAllChannels(): List<Channel> = cachedAllChannels

    fun searchChannels(query: String): List<Channel> {
        val q = query.lowercase()
        return cachedAllChannels.filter {
            it.name.lowercase().contains(q) || it.group.lowercase().contains(q)
        }
    }

    // --- Sort & Filter ---

    fun getSortedFiltered(
        sort: SortOrder,
        filter: StreamFilter,
        source: List<Channel> = cachedAllChannels
    ): List<Channel> {
        val filtered = when (filter) {
            StreamFilter.ALL  -> source
            StreamFilter.HLS  -> source.filter { it.streamType == "HLS" }
            StreamFilter.DASH -> source.filter { it.streamType == "DASH" }
            StreamFilter.RTMP -> source.filter { it.streamType == "RTMP" }
        }
        return when (sort) {
            SortOrder.DEFAULT   -> filtered
            SortOrder.NAME_ASC  -> filtered.sortedBy { it.name.lowercase() }
            SortOrder.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            SortOrder.TYPE      -> filtered.sortedBy { it.streamType }
        }
    }

    // --- Favorites ---

    suspend fun toggleFavorite(channelId: String) = withContext(Dispatchers.IO) {
        val favIds = cachedFavoriteIds.toMutableSet()
        if (favIds.contains(channelId)) favIds.remove(channelId) else favIds.add(channelId)
        prefs.edit().putStringSet("favorites", favIds).apply()
        cachedFavoriteIds = favIds
        refreshFavoritesCache()
    }

    fun isFavorite(channelId: String): Boolean = cachedFavoriteIds.contains(channelId)

    private fun loadFavoriteIds(): Set<String> =
        prefs.getStringSet("favorites", emptySet()) ?: emptySet()

    private fun refreshFavoritesCache() {
        if (cachedFavoriteIds.isEmpty()) cachedFavoriteIds = loadFavoriteIds()
        _favorites.value = cachedAllChannels
            .filter { cachedFavoriteIds.contains(it.id) }
            .map { it.copy(isFavorite = true) }
    }

    fun enrichWithFavorite(channel: Channel): Channel =
        channel.copy(isFavorite = isFavorite(channel.id))

    // --- Export / Import Favorit ---

    /**
     * Export daftar favorit ke file JSON di folder Download publik.
     * Return: path file yang dibuat, atau null jika gagal.
     */
    suspend fun exportFavorites(): File? = withContext(Dispatchers.IO) {
        try {
            val favChannels = cachedAllChannels.filter { cachedFavoriteIds.contains(it.id) }
            val arr = JSONArray()
            favChannels.forEach { ch ->
                arr.put(JSONObject().apply {
                    put("id", ch.id)
                    put("name", ch.name)
                    put("url", ch.url)
                    put("group", ch.group)
                    put("logoUrl", ch.logoUrl)
                })
            }
            val root = JSONObject().apply {
                put("version", 1)
                put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
                put("favorites", arr)
            }
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            val file = File(dir, "orbixtv_favorites.json")
            file.writeText(root.toString(2))
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Import favorit dari file JSON.
     * Return: jumlah channel yang berhasil diimport, atau -1 jika parsing gagal.
     */
    suspend fun importFavorites(file: File): Int = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("favorites")
            val ids = cachedFavoriteIds.toMutableSet()
            var count = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                if (channelById.containsKey(id) && !ids.contains(id)) {
                    ids.add(id)
                    count++
                }
            }
            prefs.edit().putStringSet("favorites", ids).apply()
            cachedFavoriteIds = ids
            refreshFavoritesCache()
            count
        } catch (e: Exception) {
            -1
        }
    }

    // --- Recently watched ---

    fun getLastWatched(): List<String> {
        val raw = prefs.getString("last_watched", "") ?: ""
        return if (raw.isEmpty()) emptyList()
        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getRecentChannels(): List<Channel> =
        getLastWatched().mapNotNull { id -> channelById[id] }

    fun addToLastWatched(channelId: String) {
        val current = getLastWatched().toMutableList()
        current.remove(channelId)
        current.add(0, channelId)
        prefs.edit().putString("last_watched", current.take(30).joinToString(",")).apply()
    }

    fun clearHistory() = prefs.edit().remove("last_watched").apply()

    // --- Sleep timer ---

    fun saveSleepTimer(minutes: Int) = prefs.edit().putInt("sleep_timer_minutes", minutes).apply()
    fun getSleepTimer(): Int = prefs.getInt("sleep_timer_minutes", 0)
}
