package com.orbixtv.app.data

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val logoUrl: String,
    val group: String,
    val userAgent: String = "",
    val licenseType: String = "",
    val licenseKey: String = "",
    val referer: String = "",
    val isFavorite: Boolean = false,
    val streamType: String = "LIVE",
    // Status ping: 0 = belum dicek, 1 = online, -1 = offline
    val pingStatus: Int = 0
)

data class ChannelGroup(
    val name: String,
    val channels: List<Channel>,
    val flagEmoji: String = ""
)

// Enum sort channel
enum class SortOrder { DEFAULT, NAME_ASC, NAME_DESC, TYPE }

// Enum filter stream type
enum class StreamFilter { ALL, HLS, DASH, RTMP }
