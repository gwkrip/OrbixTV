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

    suspend fun loadPlaylist() {
        val groups = M3uParser.parse(context)
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

    fun toggleFavorite(channelId: Int) {
        val favIds = getFavoriteIds().toMutableSet()
        if (favIds.contains(channelId.toString())) {
            favIds.remove(channelId.toString())
        } else {
            favIds.add(channelId.toString())
        }
        prefs.edit().putStringSet("favorites", favIds).apply()
        loadFavorites(getAllChannels())
    }

    fun isFavorite(channelId: Int): Boolean {
        return getFavoriteIds().contains(channelId.toString())
    }

    private fun getFavoriteIds(): Set<String> {
        return prefs.getStringSet("favorites", emptySet()) ?: emptySet()
    }

    private fun loadFavorites(allChannels: List<Channel>) {
        val favIds = getFavoriteIds()
        _favorites.value = allChannels
            .filter { favIds.contains(it.id.toString()) }
            .map { it.copy(isFavorite = true) }
    }

    fun enrichWithFavorite(channel: Channel): Channel =
        channel.copy(isFavorite = isFavorite(channel.id))

    fun getLastWatched(): List<Int> {
        val raw = prefs.getString("last_watched", "") ?: ""
        return if (raw.isEmpty()) emptyList()
        else raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    fun addToLastWatched(channelId: Int) {
        val current = getLastWatched().toMutableList()
        current.remove(channelId)
        current.add(0, channelId)
        val limited = current.take(20)
        prefs.edit().putString("last_watched", limited.joinToString(",")).apply()
    }
}
