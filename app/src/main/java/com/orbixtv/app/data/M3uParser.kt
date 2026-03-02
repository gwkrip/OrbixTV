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

    private val LOGO_REGEX      = Regex("""tvg-logo="([^"]*)"""")
    private val GROUP_REGEX     = Regex("""group-title="([^"]*)"""")
    private val NAME_REGEX      = Regex("""tvg-name="([^"]*)"""")
    // Tangkap atribut type= atau content-type= dari baris #EXTINF
    // Contoh: #EXTINF:-1 type="vnd.apple.mpegurl" ...,Channel
    private val TYPE_REGEX      = Regex("""(?:content-type|type)="([^"]*)"""", RegexOption.IGNORE_CASE)

    private val groupFlagMap: Map<String, String> = mapOf(
        "INDONESIA" to "🇮🇩", "MALAYSIA"  to "🇲🇾", "SINGAPURA" to "🇸🇬",
        "JAPAN"     to "🇯🇵", "FILIPINA"  to "🇵🇭", "ITALIA"    to "🇮🇹",
        "BRUNEI"    to "🇧🇳", "THAILAND"  to "🇹🇭", "KOREA"     to "🇰🇷",
        "CHINA"     to "🇨🇳", "USA"       to "🇺🇸", "UK"        to "🇬🇧",
        "AUSTRALIA" to "🇦🇺", "INDIA"     to "🇮🇳", "ARAB"      to "🇸🇦",
        "TURKI"     to "🇹🇷"
    )

    // Mapping MIME / vnd type string → StreamType internal.
    // Urutan penting: lebih spesifik di atas.
    private val MIME_TO_STREAM_TYPE: List<Pair<String, StreamType>> = listOf(
        // HLS — semua varian vnd / x-mpegurl
        "vnd.apple.mpegurl"          to StreamType.HLS,
        "x-mpegurl"                  to StreamType.HLS,
        "application/x-mpegurl"      to StreamType.HLS,
        "application/vnd.apple.mpegurl" to StreamType.HLS,
        "audio/mpegurl"              to StreamType.HLS,
        "audio/x-mpegurl"            to StreamType.HLS,
        // DASH — varian vnd
        "application/dash+xml"       to StreamType.DASH,
        "vnd.ms-sstr+xml"            to StreamType.DASH,   // Smooth Streaming → diperlakukan DASH
        "application/vnd.ms-sstr+xml" to StreamType.DASH,
        // Smooth Streaming ISM juga kadang muncul sebagai DASH
        "application/vnd.ms-playready.initiator+xml" to StreamType.DASH,
        // RTMP
        "video/rtmp"                 to StreamType.RTMP,
        "rtmp"                       to StreamType.RTMP,
    )

    val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // OkHttpClient khusus untuk content-type sniffing — timeout lebih pendek
    private val sniffClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
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
                        if (key.isNotEmpty() && value.isNotEmpty()) pendingKodiProps[key] = value
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

            // --- Baca atribut type= / content-type= dari #EXTINF ---
            // Ini adalah cara OTT Navigator / VLC menentukan format stream
            // tanpa harus mengandalkan ekstensi URL atau network sniffing.
            val extinfMimeHint = TYPE_REGEX.find(extinf)?.groupValues?.get(1)?.trim() ?: ""

            val streamUrl = url.substringBefore("|")
            val params    = if (url.contains("|")) url.substringAfter("|") else ""

            var userAgent   = ""
            var licenseType = ""
            var licenseKey  = ""
            var referer     = ""

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

            if (licenseType.isEmpty()) {
                val kodiLicType = kodiProps["inputstream.adaptive.license_type"] ?: ""
                if (kodiLicType.isNotEmpty()) {
                    licenseType = if (kodiLicType.equals("org.w3.clearkey", ignoreCase = true))
                        "clearkey" else kodiLicType
                }
            }
            if (licenseKey.isEmpty()) licenseKey = kodiProps["inputstream.adaptive.license_key"] ?: ""
            if (userAgent.isEmpty()) {
                val streamHeaders = kodiProps["inputstream.adaptive.stream_headers"] ?: ""
                if (streamHeaders.contains("user-agent=", ignoreCase = true)) {
                    userAgent = streamHeaders
                        .split("&", "|")
                        .firstOrNull { it.startsWith("user-agent=", ignoreCase = true) }
                        ?.substringAfter("=") ?: ""
                }
            }

            // --- Deteksi tipe stream dengan prioritas bertingkat ---
            // Prioritas 1: MIME hint dari atribut #EXTINF (type= / content-type=)
            // Prioritas 2: #KODIPROP manifest_type
            // Prioritas 3: Deteksi otomatis dari pola URL
            val kodiManifestType = kodiProps["inputstream.adaptive.manifest_type"]?.lowercase() ?: ""
            val streamType = when {
                extinfMimeHint.isNotEmpty() ->
                    mimeStringToStreamType(extinfMimeHint)
                kodiManifestType == "mpd" || kodiManifestType == "dash" -> StreamType.DASH
                kodiManifestType == "hls"                               -> StreamType.HLS
                else -> detectStreamType(streamUrl)
            }

            // Simpan mimeTypeHint mentah untuk dipakai PlayerActivity
            // saat streamType masih PROGRESSIVE (perlu sniffing runtime).
            val mimeTypeHint = when {
                extinfMimeHint.isNotEmpty() -> extinfMimeHint
                kodiManifestType.isNotEmpty() -> kodiManifestType
                else -> ""
            }

            val stableId = MessageDigest.getInstance("SHA-256")
                .digest((name + streamUrl).toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }

            Channel(
                id           = stableId,
                name         = name,
                url          = streamUrl,
                logoUrl      = logoUrl,
                group        = group,
                userAgent    = userAgent,
                licenseType  = licenseType,
                licenseKey   = licenseKey,
                referer      = referer,
                streamType   = streamType.label,
                mimeTypeHint = mimeTypeHint
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Konversi string MIME / vnd type ke StreamType internal.
     * Menerima format pendek ("vnd.apple.mpegurl") maupun penuh
     * ("application/vnd.apple.mpegurl").
     */
    fun mimeStringToStreamType(mime: String): StreamType {
        val lower = mime.lowercase().trim()
        return MIME_TO_STREAM_TYPE.firstOrNull { (pattern, _) ->
            lower.contains(pattern)
        }?.second ?: detectStreamType(lower)   // fallback ke URL detection
    }

    /**
     * Deteksi tipe stream dari pola URL.
     * Fungsi ini mencakup pola vnd/mpegurl yang biasa ditemukan
     * di URL langsung (bukan hanya di atribut EXTINF).
     */
    fun detectStreamType(url: String): StreamType {
        val lower = url.lowercase()
        return when {
            // DASH
            lower.contains(".mpd")              ||
            lower.contains("/dash/")            ||
            lower.contains("manifest.mpd")      ||
            lower.contains("/mpd/")             ||
            lower.contains("format=mpd")        ||
            lower.contains("type=dash")         ||
            lower.contains("ism/manifest")      ||
            lower.contains(".ism(.mpd)")        ||
            lower.contains("dash+xml")          -> StreamType.DASH

            // HLS — termasuk semua varian vnd / mpegurl
            lower.contains(".m3u8")             ||
            lower.contains("/hls/")             ||
            lower.contains("chunklist")         ||
            lower.contains("format=m3u8")       ||
            lower.contains("vnd.apple.mpegurl") ||
            lower.contains("x-mpegurl")         ||
            lower.contains("mpegurl")           -> StreamType.HLS

            // RTMP
            lower.startsWith("rtmp://")         ||
            lower.startsWith("rtmps://")        -> StreamType.RTMP

            else -> StreamType.PROGRESSIVE
        }
    }

    /**
     * Sniff Content-Type via HTTP HEAD request.
     * Dipakai oleh PlayerActivity untuk URL dengan StreamType.PROGRESSIVE
     * (tidak terdeteksi dari pola URL maupun atribut #EXTINF).
     * Return null jika request gagal atau timeout.
     */
    suspend fun sniffContentType(
        url: String,
        userAgent: String = "",
        referer: String = ""
    ): StreamType? = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(url).head()
            if (userAgent.isNotEmpty()) reqBuilder.header("User-Agent", userAgent)
            if (referer.isNotEmpty())   reqBuilder.header("Referer", referer)

            sniffClient.newCall(reqBuilder.build()).execute().use { response ->
                val contentType = response.header("Content-Type") ?: return@withContext null
                val result = mimeStringToStreamType(contentType)
                // Hanya kembalikan jika hasilnya informatif (bukan PROGRESSIVE lagi)
                if (result != StreamType.PROGRESSIVE) result else null
            }
        } catch (e: Exception) {
            null
        }
    }

    enum class StreamType(val label: String) {
        HLS("HLS"),
        DASH("DASH"),
        RTMP("RTMP"),
        PROGRESSIVE("PROGRESSIVE")
    }
}
