package com.orbixtv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object M3uParser {

    private val LOGO_REGEX  = Regex("""tvg-logo="([^"]*)"""")
    private val GROUP_REGEX = Regex("""group-title="([^"]*)"""")
    private val NAME_REGEX  = Regex("""tvg-name="([^"]*)"""")

    private val groupFlagMap: Map<String, String> = mapOf(
        "INDONESIA" to "🇮🇩",
        "MALAYSIA"  to "🇲🇾",
        "SINGAPURA" to "🇸🇬",
        "JAPAN"     to "🇯🇵",
        "FILIPINA"  to "🇵🇭",
        "ITALIA"    to "🇮🇹",
        "BRUNEI"    to "🇧🇳",
        "THAILAND"  to "🇹🇭",
        "KOREA"     to "🇰🇷",
        "CHINA"     to "🇨🇳",
        "USA"       to "🇺🇸",
        "UK"        to "🇬🇧",
        "AUSTRALIA" to "🇦🇺",
        "INDIA"     to "🇮🇳",
        "ARAB"      to "🇸🇦",
        "TURKI"     to "🇹🇷"
    )

    // ============================================================
    // BUG #16 FIX: Ekspos sharedHttpClient agar ChannelRepository
    // bisa memakainya untuk HEAD request (followRedirects=true).
    // ============================================================
    val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)   // Tambahan: HTTPS → HTTP redirect
            .build()
    }

    suspend fun parseFromAssets(context: Context): List<ChannelGroup> = withContext(Dispatchers.IO) {
        try {
            context.assets.open("playlist.m3u8").use { inputStream ->
                parseFromReader(BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================================================
    // BUG #11 FIX: response.use { } memastikan response body dan
    // koneksi ditutup meski byteStream() throw exception di tengah.
    // ============================================================
    suspend fun parseFromUrl(url: String): Result<List<ChannelGroup>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            sharedHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code}: ${response.message}")
                    )
                }
                val groups = response.body?.byteStream()?.use { stream ->
                    parseFromReader(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
                } ?: return@withContext Result.failure(Exception("Response body kosong"))

                Result.success(groups)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseFromReader(reader: BufferedReader): List<ChannelGroup> {
        val channels = mutableListOf<Channel>()
        var pendingExtInf: String? = null
        val pendingKodiProps = mutableMapOf<String, String>()

        reader.use {
            var line = it.readLine()
            while (line != null) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("#EXTINF:") -> {
                        pendingExtInf = trimmed
                        pendingKodiProps.clear()
                    }
                    trimmed.startsWith("#KODIPROP:") && pendingExtInf != null -> {
                        val prop  = trimmed.removePrefix("#KODIPROP:")
                        val key   = prop.substringBefore("=").trim()
                        val value = prop.substringAfter("=", "").trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            pendingKodiProps[key] = value
                        }
                    }
                    trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingExtInf != null -> {
                        parseChannel(pendingExtInf!!, trimmed, pendingKodiProps)
                            ?.let { ch -> channels.add(ch) }
                        pendingExtInf = null
                        pendingKodiProps.clear()
                    }
                }
                line = it.readLine()
            }
        }

        return groupChannels(channels)
    }

    fun parseContent(content: String): List<ChannelGroup> {
        val channels = mutableListOf<Channel>()
        var pendingExtInf: String? = null
        val pendingKodiProps = mutableMapOf<String, String>()
        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            when {
                trimmed.startsWith("#EXTINF:") -> {
                    pendingExtInf = trimmed
                    pendingKodiProps.clear()
                }
                trimmed.startsWith("#KODIPROP:") && pendingExtInf != null -> {
                    val prop  = trimmed.removePrefix("#KODIPROP:")
                    val key   = prop.substringBefore("=").trim()
                    val value = prop.substringAfter("=", "").trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) pendingKodiProps[key] = value
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingExtInf != null -> {
                    parseChannel(pendingExtInf!!, trimmed, pendingKodiProps)?.let { channels.add(it) }
                    pendingExtInf = null
                    pendingKodiProps.clear()
                }
            }
        }
        return groupChannels(channels)
    }

    private fun groupChannels(channels: List<Channel>): List<ChannelGroup> {
        return channels.groupBy { it.group }.map { (group, channelList) ->
            val groupUpper = group.uppercase()
            val flag = groupFlagMap.entries.firstOrNull { (key, _) ->
                groupUpper.contains(key)
            }?.value ?: "📺"
            ChannelGroup(
                name      = group.ifEmpty { "Lainnya" },
                channels  = channelList,
                flagEmoji = flag
            )
        }.sortedBy { it.name }
    }

    private fun parseChannel(
        extinf: String,
        url: String,
        kodiProps: Map<String, String> = emptyMap()
    ): Channel? {
        return try {
            val displayName = extinf.substringAfterLast(",").trim()
            val tvgName     = NAME_REGEX.find(extinf)?.groupValues?.get(1) ?: ""
            val name        = displayName.ifEmpty { tvgName.ifEmpty { "Unknown" } }
            val logoUrl     = LOGO_REGEX.find(extinf)?.groupValues?.get(1) ?: ""
            val group       = GROUP_REGEX.find(extinf)?.groupValues?.get(1) ?: "Lainnya"

            val streamUrl = url.substringBefore("|")
            val params    = if (url.contains("|")) url.substringAfter("|") else ""

            var userAgent   = ""
            var licenseType = ""
            var licenseKey  = ""
            var referer     = ""

            // --- 1. Baca dari pipe params di URL ---
            params.split("&").forEach { param ->
                when {
                    param.startsWith("User-Agent=", ignoreCase = true) ->
                        userAgent   = param.substringAfter("=")
                    param.startsWith("license_type=") ->
                        licenseType = param.substringAfter("license_type=")
                    param.startsWith("license_key=")  ->
                        licenseKey  = param.substringAfter("license_key=")
                    param.startsWith("referrer=", ignoreCase = true) ->
                        referer     = param.substringAfter("=").trim('"')
                    param.startsWith("Referer=") ->
                        referer     = param.substringAfter("Referer=").trim('"')
                }
            }

            // ============================================================
            // BUG #15 FIX: Hapus double-parsing User-Agent dari full URL.
            // Parsing sudah dilakukan dari params di atas dengan split("&").
            // Blok redundan sebelumnya mengambil substring dari URL mentah
            // yang bisa salah jika ada query params tambahan.
            // ============================================================

            // --- 2. Baca dari #KODIPROP ---
            if (licenseType.isEmpty()) {
                val kodiLicType = kodiProps["inputstream.adaptive.license_type"] ?: ""
                if (kodiLicType.isNotEmpty()) {
                    licenseType = if (kodiLicType.equals("org.w3.clearkey", ignoreCase = true))
                        "clearkey" else kodiLicType
                }
            }
            if (licenseKey.isEmpty()) {
                licenseKey = kodiProps["inputstream.adaptive.license_key"] ?: ""
            }
            if (userAgent.isEmpty()) {
                val streamHeaders = kodiProps["inputstream.adaptive.stream_headers"] ?: ""
                if (streamHeaders.contains("user-agent=", ignoreCase = true)) {
                    userAgent = streamHeaders
                        .split("&", "|")
                        .firstOrNull { it.startsWith("user-agent=", ignoreCase = true) }
                        ?.substringAfter("=") ?: ""
                }
            }

            // --- 3. Deteksi tipe stream ---
            val kodiManifestType = kodiProps["inputstream.adaptive.manifest_type"]?.lowercase() ?: ""
            val streamType = when {
                kodiManifestType == "mpd" || kodiManifestType == "dash" -> StreamType.DASH
                kodiManifestType == "hls"                               -> StreamType.HLS
                else                                                    -> detectStreamType(streamUrl)
            }

            // ============================================================
            // BUG #10 FIX: Ganti Int.hashCode() (32-bit, collision prone)
            // dengan 8 byte SHA-256 → collision probability mendekati nol
            // bahkan untuk ratusan ribu channel.
            // ============================================================
            val stableId = MessageDigest.getInstance("SHA-256")
                .digest((name + streamUrl).toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }

            Channel(
                id          = stableId,
                name        = name,
                url         = streamUrl,
                logoUrl     = logoUrl,
                group       = group,
                userAgent   = userAgent,
                licenseType = licenseType,
                licenseKey  = licenseKey,
                referer     = referer,
                streamType  = streamType.label
            )
        } catch (e: Exception) {
            null
        }
    }

    fun detectStreamType(url: String): StreamType {
        val lower = url.lowercase()
        return when {
            lower.contains(".mpd")         ||
            lower.contains("/dash/")       ||
            lower.contains("manifest.mpd") ||
            lower.contains("/mpd/")        ||
            lower.contains("format=mpd")   ||
            lower.contains("type=dash")    ||
            lower.contains("ism/manifest") ||
            lower.contains(".ism(.mpd)")   -> StreamType.DASH

            lower.contains(".m3u8")        ||
            lower.contains("/hls/")        ||
            lower.contains("chunklist")    ||
            lower.contains("format=m3u8")  -> StreamType.HLS

            lower.startsWith("rtmp://")    ||
            lower.startsWith("rtmps://")   -> StreamType.RTMP

            // ============================================================
            // BUG #12 FIX: Label diubah menjadi "PROGRESSIVE" agar badge
            // "● LIVE" tidak muncul untuk file VOD seperti .mp4 biasa.
            // ============================================================
            else -> StreamType.PROGRESSIVE
        }
    }

    enum class StreamType(val label: String) {
        HLS("HLS"),
        DASH("DASH"),
        RTMP("RTMP"),
        PROGRESSIVE("PROGRESSIVE")   // BUG #12 FIX: Bukan "LIVE"
    }
}
