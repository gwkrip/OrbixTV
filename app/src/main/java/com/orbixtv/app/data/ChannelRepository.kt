package com.orbixtv.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChannelRepository private constructor(private val context: Context) {

    companion object {
        @Volatile private var INSTANCE: ChannelRepository? = null

        const val DEFAULT_PLAYLIST_URL = "https://raw.githubusercontent.com/gwkrip/iptv-playlist/refs/heads/main/index.m3u"

        fun getInstance(context: Context): ChannelRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChannelRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("orbixtv_prefs", Context.MODE_PRIVATE)
    }

    private val _groups = MutableStateFlow<List<ChannelGroup>>(emptyList())
    val groups: StateFlow<List<ChannelGroup>> = _groups

    private val _favorites = MutableStateFlow<List<Channel>>(emptyList())
    val favorites: StateFlow<List<Channel>> = _favorites

    private var cachedAllChannels: List<Channel> = emptyList()
    private var channelById: Map<String, Channel> = emptyMap()

    @Volatile private var isPlaylistLoaded = false

    private val favoritesMutex = Mutex()
    private var cachedFavoriteIds: Set<String> = emptySet()
    private var isFavoritesLoaded = false

    /** Mengembalikan URL aktif — URL kustom user jika ada, atau DEFAULT_PLAYLIST_URL */
    fun getPlaylistUrl(): String {
        val saved = prefs.getString("playlist_url", null)
        return if (!saved.isNullOrBlank()) saved else DEFAULT_PLAYLIST_URL
    }

    /** true jika user belum menyimpan URL kustom (menggunakan URL default) */
    fun isUsingDefaultUrl(): Boolean = prefs.getString("playlist_url", null).isNullOrBlank()

    fun savePlaylistUrl(url: String) {
        isPlaylistLoaded = false
        prefs.edit().putString("playlist_url", url.trim()).apply()
    }

    /** Reset ke URL default (hapus URL kustom user) */
    fun resetToDefaultUrl() {
        isPlaylistLoaded = false
        prefs.edit().remove("playlist_url").apply()
    }

    @Deprecated("Gunakan resetToDefaultUrl()", ReplaceWith("resetToDefaultUrl()"))
    fun clearPlaylistUrl() = resetToDefaultUrl()

    suspend fun loadPlaylist(): String? {
        if (isPlaylistLoaded && cachedAllChannels.isNotEmpty()) return null

        val url = getPlaylistUrl() // selalu ada URL (default atau kustom)
        val result = M3uParser.parseFromUrl(url)
        return if (result.isSuccess) {
            applyGroups(result.getOrNull() ?: emptyList())
            isPlaylistLoaded = true
            null
        } else {
            // Fallback ke assets hanya jika URL default pun gagal
            loadFromAssets()
            isPlaylistLoaded = true
            "Gagal memuat dari URL ($url): ${result.exceptionOrNull()?.message ?: "Error tidak diketahui"}"
        }
    }

    suspend fun reloadPlaylist(): String? {
        isPlaylistLoaded = false
        return loadPlaylist()
    }

    suspend fun isPlaylistUrlReachable(): Boolean = withContext(Dispatchers.IO) {
        val url = getPlaylistUrl().ifEmpty { return@withContext true }
        try {
            val request = okhttp3.Request.Builder().url(url).head().build()
            M3uParser.sharedHttpClient.newCall(request).execute().use { response ->
                response.code in 200..399
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun loadFromAssets() {
        applyGroups(M3uParser.parseFromAssets(context))
    }

    private suspend fun applyGroups(groups: List<ChannelGroup>) {
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

    suspend fun toggleFavorite(channelId: String) = withContext(Dispatchers.IO) {
        favoritesMutex.withLock {
            val favIds = cachedFavoriteIds.toMutableSet()
            if (favIds.contains(channelId)) favIds.remove(channelId) else favIds.add(channelId)
            prefs.edit().putStringSet("favorites", favIds).apply()
            cachedFavoriteIds = favIds
        }
        refreshFavoritesCache()
    }

    fun isFavorite(channelId: String): Boolean = cachedFavoriteIds.contains(channelId)

    private fun loadFavoriteIds(): Set<String> =
        prefs.getStringSet("favorites", emptySet()) ?: emptySet()

    private suspend fun refreshFavoritesCache() {
        favoritesMutex.withLock {
            if (!isFavoritesLoaded) {
                cachedFavoriteIds = loadFavoriteIds()
                isFavoritesLoaded = true
            }
        }
        _favorites.value = cachedAllChannels
            .filter { cachedFavoriteIds.contains(it.id) }
            .map { it.copy(isFavorite = true) }
    }

    fun enrichWithFavorite(channel: Channel): Channel =
        channel.copy(isFavorite = isFavorite(channel.id))

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

    suspend fun importFavorites(file: File): Int = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("favorites")
            var count = 0
            favoritesMutex.withLock {
                val ids = cachedFavoriteIds.toMutableSet()
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
            }
            refreshFavoritesCache()
            count
        } catch (e: Exception) {
            -1
        }
    }

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

    fun saveSleepTimer(minutes: Int) = prefs.edit().putInt("sleep_timer_minutes", minutes).apply()
    fun getSleepTimer(): Int = prefs.getInt("sleep_timer_minutes", 0)
    fun clearSleepTimer() = prefs.edit().remove("sleep_timer_minutes").apply()
}
