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

    // ============================================================
    // BUG #1 + #14 FIX: Singleton — PlayerActivity & PlaylistSettings-
    // Activity kini berbagi instance yang sama dengan MainViewModel,
    // sehingga channel list tidak pernah kosong di PlayerActivity.
    // ============================================================
    companion object {
        @Volatile private var INSTANCE: ChannelRepository? = null

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

    // Guard untuk mencegah double-load di SplashActivity → MainViewModel.
    // Di-reset ke false setiap kali user mengganti playlist URL secara manual.
    @Volatile private var isPlaylistLoaded = false

    // ============================================================
    // BUG #8 + #9 FIX: Mutex untuk thread-safety pada cachedFavorite-
    // Ids, dan flag isFavoritesLoaded agar kondisi isEmpty tidak
    // ambigu ketika user memang punya 0 favorit.
    // ============================================================
    private val favoritesMutex = Mutex()
    private var cachedFavoriteIds: Set<String> = emptySet()
    private var isFavoritesLoaded = false

    // --- Playlist URL ---

    fun getPlaylistUrl(): String = prefs.getString("playlist_url", "") ?: ""
    fun savePlaylistUrl(url: String) {
        isPlaylistLoaded = false   // paksa reload saat URL baru disimpan
        prefs.edit().putString("playlist_url", url.trim()).apply()
    }
    fun clearPlaylistUrl() {
        isPlaylistLoaded = false   // paksa reload saat URL dihapus
        prefs.edit().remove("playlist_url").apply()
    }

    // --- Load playlist ---

    /**
     * Muat playlist dari URL eksternal atau assets bawaan.
     *
     * Guard [isPlaylistLoaded] memastikan tidak ada double-load:
     * SplashActivity memanggil ini langsung, lalu MainViewModel.init{}
     * memanggil lagi — panggilan kedua akan di-skip jika data sudah ada.
     *
     * Return: pesan error jika URL gagal, null jika sukses.
     */
    suspend fun loadPlaylist(): String? {
        if (isPlaylistLoaded && cachedAllChannels.isNotEmpty()) return null

        val url = getPlaylistUrl()
        return if (url.isNotEmpty()) {
            val result = M3uParser.parseFromUrl(url)
            if (result.isSuccess) {
                applyGroups(result.getOrNull() ?: emptyList())
                isPlaylistLoaded = true
                null
            } else {
                loadFromAssets()
                isPlaylistLoaded = true
                "Gagal memuat dari URL: ${result.exceptionOrNull()?.message ?: "Error tidak diketahui"}"
            }
        } else {
            loadFromAssets()
            isPlaylistLoaded = true
            null
        }
    }

    /** Muat ulang paksa — dipanggil saat user mengganti URL atau reset. */
    suspend fun reloadPlaylist(): String? {
        isPlaylistLoaded = false
        return loadPlaylist()
    }

    // ============================================================
    // BUG #16 FIX: Gunakan sharedHttpClient (followRedirects=true)
    // agar redirect HTTPS→HTTP diikuti secara otomatis.
    // ============================================================
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

    // BUG #8 FIX: favoritesMutex.withLock() menjamin tidak ada race
    // condition antara toggleFavorite (IO) dan applyGroups.
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

    // BUG #9 FIX: Flag isFavoritesLoaded terpisah — bukan isEmpty().
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

    // --- Export / Import Favorit ---

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

    // BUG #6 FIX: Hapus nilai tersimpan agar timer tidak tersisa di prefs.
    fun clearSleepTimer() = prefs.edit().remove("sleep_timer_minutes").apply()
}
