package com.orbixtv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object M3uParser {

    private val LOGO_REGEX  = Regex("""tvg-logo="([^"]*)"""")
    private val GROUP_REGEX = Regex("""group-title="([^"]*)"""")
    private val NAME_REGEX  = Regex("""tvg-name="([^"]*)"""")

    // #8: Key sudah uppercase saat inisialisasi — tidak perlu .uppercase() ulang saat lookup
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

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Parse dari assets — stream langsung, tidak buffer seluruh file ke String */
    suspend fun parseFromAssets(context: Context): List<ChannelGroup> = withContext(Dispatchers.IO) {
        try {
            // #7: Baca langsung dari InputStream tanpa .readText() ke String perantara
            context.assets.open("playlist.m3u8").use { inputStream ->
                parseFromReader(BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Parse dari URL — stream response body langsung tanpa .string() ke RAM */
    suspend fun parseFromUrl(url: String): Result<List<ChannelGroup>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
            // #7: byteStream() → parse on-the-fly, tidak perlu tampung seluruh file di memory
            val groups = response.body?.byteStream()?.use { stream ->
                parseFromReader(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
            } ?: return@withContext Result.failure(Exception("Response body kosong"))

            Result.success(groups)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Core parser: baca per-baris dari Reader (shared antara assets & URL) */
    private fun parseFromReader(reader: BufferedReader): List<ChannelGroup> {
        val channels = mutableListOf<Channel>()
        var pendingExtInf: String? = null
        // FIX: Kumpulkan #KODIPROP antara #EXTINF dan URL — sebelumnya diabaikan → DRM tidak terbaca → blackscreen
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
                    // Tangkap semua #KODIPROP yang berisi konfigurasi DRM/stream
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

    /** Backward-compat: parse dari String (dipakai di settings preview / test) */
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
            // #8: uppercase() hanya sekali per grup; key di map sudah uppercase dari awal
            val groupUpper = group.uppercase()
            val flag = groupFlagMap.entries.firstOrNull { (key, _) ->
                groupUpper.contains(key)
            }?.value ?: "📺"
            ChannelGroup(
                name     = group.ifEmpty { "Lainnya" },
                channels = channelList,
                flagEmoji = flag
            )
        }.sortedBy { it.name }
    }

    /**
     * FIX: Tambah parameter kodiProps untuk membaca #KODIPROP DRM config.
     * Priority: URL pipe params (license_type=, license_key=) > #KODIPROP lines.
     *
     * #KODIPROP yang didukung:
     *   inputstream.adaptive.license_type  → licenseType
     *   inputstream.adaptive.license_key   → licenseKey
     *   inputstream.adaptive.manifest_type → hint tipe stream (mpd = DASH)
     */
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

            // --- 1. Baca dari pipe params di URL (format Kodi/IPTV lama) ---
            params.split("&").forEach { param ->
                when {
                    param.startsWith("User-Agent=")   -> userAgent   = param.substringAfter("User-Agent=")
                    param.startsWith("license_type=") -> licenseType = param.substringAfter("license_type=")
                    param.startsWith("license_key=")  -> licenseKey  = param.substringAfter("license_key=")
                    param.startsWith("referrer=")     -> referer     = param.substringAfter("referrer=").trim('"')
                    param.startsWith("Referer=")      -> referer     = param.substringAfter("Referer=").trim('"')
                }
            }

            if (userAgent.isEmpty() && url.contains("User-Agent=")) {
                val uaSection = url.substringAfter("User-Agent=")
                userAgent = uaSection.substringBefore("|").substringBefore("&")
                if (userAgent.contains("referrer=")) {
                    referer   = userAgent.substringAfter("referrer=").trim().trim('"')
                    userAgent = userAgent.substringBefore("referrer=").trim()
                }
            }

            // --- 2. FIX: Baca dari #KODIPROP jika URL params tidak menyediakan DRM config ---
            // #KODIPROP: inputstream.adaptive.license_type  (clearkey, org.w3.clearkey, com.widevine.alpha, dll)
            if (licenseType.isEmpty()) {
                val kodiLicType = kodiProps["inputstream.adaptive.license_type"] ?: ""
                if (kodiLicType.isNotEmpty()) {
                    // Normalisasi: "org.w3.clearkey" dan "clearkey" diperlakukan sama
                    licenseType = if (kodiLicType.equals("org.w3.clearkey", ignoreCase = true))
                        "clearkey" else kodiLicType
                }
            }

            // #KODIPROP: inputstream.adaptive.license_key  (format hex: "kid:key" atau "kid1:key1,kid2:key2")
            if (licenseKey.isEmpty()) {
                licenseKey = kodiProps["inputstream.adaptive.license_key"] ?: ""
            }

            // #KODIPROP: user-agent / stream_headers (format "Key=Value")
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
            // Beri hint dari #KODIPROP manifest_type jika URL tidak cukup jelas
            val kodiManifestType = kodiProps["inputstream.adaptive.manifest_type"]?.lowercase() ?: ""
            val streamType = when {
                kodiManifestType == "mpd" || kodiManifestType == "dash" -> StreamType.DASH
                kodiManifestType == "hls"                               -> StreamType.HLS
                else                                                    -> detectStreamType(streamUrl)
            }

            val stableId = (name + streamUrl).hashCode().toString()

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
            // DASH: pola URL yang umum dipakai provider IPTV
            lower.contains(".mpd") ||
            lower.contains("/dash/") ||
            lower.contains("manifest.mpd") ||
            lower.contains("/mpd/") ||
            lower.contains("format=mpd") ||
            lower.contains("type=dash") ||
            lower.contains("ism/manifest") ||
            lower.contains(".ism(.mpd)") -> StreamType.DASH

            // HLS
            lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("chunklist") ||
            lower.contains("format=m3u8") -> StreamType.HLS

            // RTMP
            lower.startsWith("rtmp://") ||
            lower.startsWith("rtmps://") -> StreamType.RTMP

            else -> StreamType.PROGRESSIVE
        }
    }

    enum class StreamType(val label: String) {
        HLS("HLS"),
        DASH("DASH"),
        RTMP("RTMP"),
        PROGRESSIVE("LIVE")
    }
}
