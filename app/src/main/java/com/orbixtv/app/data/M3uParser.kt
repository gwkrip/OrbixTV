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

        reader.use {
            var line = it.readLine()
            while (line != null) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("#EXTINF:") -> pendingExtInf = trimmed
                    trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingExtInf != null -> {
                        parseChannel(pendingExtInf!!, trimmed)?.let { ch -> channels.add(ch) }
                        pendingExtInf = null
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
        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            when {
                trimmed.startsWith("#EXTINF:") -> pendingExtInf = trimmed
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingExtInf != null -> {
                    parseChannel(pendingExtInf!!, trimmed)?.let { channels.add(it) }
                    pendingExtInf = null
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

    private fun parseChannel(extinf: String, url: String): Channel? {
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

            params.split("&").forEach { param ->
                when {
                    param.startsWith("User-Agent=")   -> userAgent   = param.substringAfter("User-Agent=")
                    param.startsWith("license_type=") -> licenseType = param.substringAfter("license_type=")
                    param.startsWith("license_key=")  -> licenseKey  = param.substringAfter("license_key=")
                    param.startsWith("referrer=")     -> referer     = param.substringAfter("referrer=").trim('"')
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

            val stableId = (name + streamUrl).hashCode().toString()

            // #10: streamType dihitung SEKALI saat parse, disimpan di Channel — tidak perlu ulang di adapter
            val streamType = detectStreamType(streamUrl)

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
