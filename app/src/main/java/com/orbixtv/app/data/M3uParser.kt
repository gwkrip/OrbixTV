package com.orbixtv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.TimeUnit

object M3uParser {

    private val LOGO_REGEX  = Regex("""tvg-logo="([^"]*)"""")
    private val GROUP_REGEX = Regex("""group-title="([^"]*)"""")
    private val NAME_REGEX  = Regex("""tvg-name="([^"]*)"""")

    private val groupFlagMap = mapOf(
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

    /** Parse dari assets (playlist lokal bawaan) */
    suspend fun parseFromAssets(context: Context): List<ChannelGroup> = withContext(Dispatchers.IO) {
        try {
            val content = context.assets.open("playlist.m3u").bufferedReader().use { it.readText() }
            parseContent(content)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Parse dari URL eksternal — download lalu parse */
    suspend fun parseFromUrl(url: String): Result<List<ChannelGroup>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
            val content = response.body?.string()
                ?: return@withContext Result.failure(Exception("Response body kosong"))
            Result.success(parseContent(content))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Core parsing logic bekerja untuk konten M3U apapun */
    fun parseContent(content: String): List<ChannelGroup> {
        val channels = mutableListOf<Channel>()
        val reader = BufferedReader(StringReader(content))
        var line: String?
        var pendingExtInf: String? = null

        while (reader.readLine().also { line = it } != null) {
            val trimmed = line?.trim() ?: continue
            when {
                trimmed.startsWith("#EXTINF:") -> pendingExtInf = trimmed
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingExtInf != null -> {
                    parseChannel(pendingExtInf!!, trimmed)?.let { channels.add(it) }
                    pendingExtInf = null
                }
            }
        }

        return channels.groupBy { it.group }.map { (group, channelList) ->
            val flag = groupFlagMap.entries.firstOrNull { (key, _) ->
                group.uppercase().contains(key.uppercase())
            }?.value ?: "📺"
            ChannelGroup(
                name = group.ifEmpty { "Lainnya" },
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

            // Stable ID: hash dari nama+URL — tidak bergeser walau urutan playlist berubah
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
                referer     = referer
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Deteksi tipe stream secara akurat */
    fun detectStreamType(url: String): StreamType {
        val lower = url.lowercase()
        return when {
            lower.contains(".mpd") || lower.contains("/dash/") || lower.contains("manifest.mpd") -> StreamType.DASH
            lower.contains(".m3u8") || lower.contains("/hls/") || lower.contains("chunklist")    -> StreamType.HLS
            lower.startsWith("rtmp://") || lower.startsWith("rtmps://")                          -> StreamType.RTMP
            else -> StreamType.PROGRESSIVE
        }
    }

    enum class StreamType { HLS, DASH, RTMP, PROGRESSIVE }
}
