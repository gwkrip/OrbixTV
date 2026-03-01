package com.orbixtv.app.data

data class Channel(
    val id: Int,
    val name: String,
    val url: String,
    val logoUrl: String,
    val group: String,
    val userAgent: String = "",
    val licenseType: String = "",
    val licenseKey: String = "",
    val referer: String = "",
    val isFavorite: Boolean = false  // passed in from ChannelRepository based on SharedPreferences
)

data class ChannelGroup(
    val name: String,
    val channels: List<Channel>,
    val flagEmoji: String = ""
)
