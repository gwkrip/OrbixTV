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
    val streamType: String = "PROGRESSIVE",
    // Hint MIME type eksplisit dari atribut #EXTINF (type=, content-type=).
    // Kosong = tidak ada hint, PlayerActivity akan sniff dari URL / HTTP header.
    val mimeTypeHint: String = "",
    val pingStatus: Int = 0
)

data class ChannelGroup(
    val name: String,
    val channels: List<Channel>,
    val flagEmoji: String = ""
)

enum class SortOrder { DEFAULT, NAME_ASC, NAME_DESC, TYPE }
enum class StreamFilter { ALL, HLS, DASH, RTMP }
