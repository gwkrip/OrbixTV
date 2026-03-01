package com.orbixtv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object M3uParser {

    private val LOGO_REGEX  = Regex("""tvg-logo="([^"]*)"""")
    private val GROUP_REGEX = Regex("""group-title="([^"]*)"""")

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

    suspend fun parse(context: Context): List<ChannelGroup> = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        var channelId = 0

        try {
            val inputStream = context.assets.open("playlist.m3u")
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var pendingExtInf: String? = null

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: continue
                when {
                    trimmed.startsWith("#EXTINF:") -> {
                        pendingExtInf = trimmed
                    }
                    trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingExtInf != null -> {
                        val channel = parseChannel(channelId++, pendingExtInf!!, trimmed)
                        if (channel != null) channels.add(channel)
                        pendingExtInf = null
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val grouped = channels.groupBy { it.group }
        grouped.map { (group, channelList) ->
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

    private fun parseChannel(id: Int, extinf: String, url: String): Channel? {
        return try {
            val name     = extinf.substringAfterLast(",").trim()
            val logoUrl  = LOGO_REGEX.find(extinf)?.groupValues?.get(1) ?: ""
            val group    = GROUP_REGEX.find(extinf)?.groupValues?.get(1) ?: "Lainnya"

            val rawUrl    = url
            val streamUrl = rawUrl.substringBefore("|")
            val params    = if (rawUrl.contains("|")) rawUrl.substringAfter("|") else ""

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

            if (userAgent.isEmpty() && rawUrl.contains("User-Agent=")) {
                val uaSection = rawUrl.substringAfter("User-Agent=")
                userAgent = uaSection.substringBefore("|").substringBefore("&")
                if (userAgent.contains("referrer=")) {
                    referer   = userAgent.substringAfter("referrer=").trim().trim('"')
                    userAgent = userAgent.substringBefore("referrer=").trim()
                }
            }

            Channel(
                id          = id,
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
}
