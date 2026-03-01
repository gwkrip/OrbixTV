package com.orbixtv.app.data

data class Channel(
    val id: String,          // Stable ID: hash dari name+url, tidak bergeser saat playlist update
    val name: String,
    val url: String,
    val logoUrl: String,
    val group: String,
    val userAgent: String = "",
    val licenseType: String = "",
    val licenseKey: String = "",
    val referer: String = "",
    val isFavorite: Boolean = false,
    // Dihitung SEKALI saat parsing, tidak diulang setiap bind()
    val streamType: String = "LIVE"
)

data class ChannelGroup(
    val name: String,
    val channels: List<Channel>,
    val flagEmoji: String = ""
)
