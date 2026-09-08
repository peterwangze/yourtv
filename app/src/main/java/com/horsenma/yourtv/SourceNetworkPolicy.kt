package com.horsenma.yourtv

import java.net.URI

/**
 * Public IPTV URLs are often tied to one carrier or province even though they
 * look like ordinary HTTP links. This policy keeps the first choices useful
 * across China Telecom, China Unicom and China Mobile networks and prevents a
 * single host from occupying every fallback slot.
 */
object SourceNetworkPolicy {
    enum class Carrier {
        PUBLIC,
        CHINA_MOBILE,
        CHINA_UNICOM,
        CHINA_TELECOM,
        PRIVATE,
        UNKNOWN,
    }

    private val publicMarkers = listOf(
        "cctv.cn", "cnr.cn", "cztv.com", "cztvcloud.com", "mgtv.com",
        "qingting.fm", "iqilu.com", "jxtvcn.com.cn", "nmtv.cn",
        "hebtv.com", "hntv.tv", "jlntv.cn", "ybtvyun.com", "qhbtv.com",
        "myalicdn.com", "kwimgs.com", "akamaized.net", "cloudfront.net",
    )

    private val mobileMarkers = listOf(
        "chinamobile.com", "cmvideo.cn", "miguvideo.com", "gmcc.net",
        "mobaibox.com", "gitv.tv",
    )

    private val unicomMarkers = listOf("chinaunicom.cn", "unicom", "wo.cn")
    private val telecomMarkers = listOf("chinatelecom", "dxhmt.cn", "189.cn", "ctcdn")

    fun carrier(url: String): Carrier {
        val host = hostOf(url).lowercase()
        if (host.isBlank()) return Carrier.UNKNOWN
        if (isPrivateHost(host)) return Carrier.PRIVATE
        if (publicMarkers.any { host == it || host.endsWith(".$it") }) return Carrier.PUBLIC
        if (mobileMarkers.any { host.contains(it) }) return Carrier.CHINA_MOBILE
        if (unicomMarkers.any { host.contains(it) }) return Carrier.CHINA_UNICOM
        if (telecomMarkers.any { host.contains(it) }) return Carrier.CHINA_TELECOM

        val normalized = host.removePrefix("[").removeSuffix("]")
        return when {
            normalized.startsWith("2409:") -> Carrier.CHINA_MOBILE
            normalized.startsWith("2408:") -> Carrier.CHINA_UNICOM
            normalized.startsWith("240e:") -> Carrier.CHINA_TELECOM
            normalized.startsWith("39.134.") || normalized.startsWith("39.135.") ||
                normalized.startsWith("39.136.") || normalized.startsWith("183.207.") -> Carrier.CHINA_MOBILE
            normalized.startsWith("221.6.") || normalized.startsWith("221.7.") ||
                normalized.startsWith("58.248.") -> Carrier.CHINA_UNICOM
            normalized.startsWith("61.136.") || normalized.startsWith("219.147.") ||
                normalized.startsWith("222.169.") -> Carrier.CHINA_TELECOM
            else -> Carrier.UNKNOWN
        }
    }

    /** Higher means more likely to work across carrier boundaries. */
    fun compatibilityScore(url: String): Int = when (carrier(url)) {
        Carrier.PUBLIC -> 40
        Carrier.UNKNOWN -> 25
        Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, Carrier.CHINA_TELECOM -> 15
        Carrier.PRIVATE -> -1_000
    }

    fun isUsablePublicCandidate(url: String): Boolean {
        if (url.isBlank() || carrier(url) == Carrier.PRIVATE) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        if (path.endsWith(".mp4") || path.endsWith(".m4a") ||
            path.endsWith(".mp3") || path.endsWith(".aac")) return false
        return when (uri.scheme?.lowercase()) {
            "http", "https", "rtmp", "rtsp" -> true
            else -> false
        }
    }

    /**
     * Keep the ranked head, then reserve a fallback from each carrier when it
     * exists. The final fill is limited to two URLs per host.
     */
    fun diversify(ranked: List<String>, limit: Int = 8): List<String> {
        if (limit <= 0) return emptyList()
        val candidates = ranked.distinct().filter(::isUsablePublicCandidate)
        if (candidates.size <= 1) return candidates.take(limit)

        val selected = LinkedHashSet<String>()
        val hostCounts = mutableMapOf<String, Int>()

        fun add(url: String, maxPerHost: Int): Boolean {
            if (selected.size >= limit || url in selected) return false
            val host = hostOf(url)
            if ((hostCounts[host] ?: 0) >= maxPerHost) return false
            selected.add(url)
            hostCounts[host] = (hostCounts[host] ?: 0) + 1
            return true
        }

        candidates.firstOrNull()?.let { add(it, 2) }
        candidates.drop(1).forEach { if (selected.size < minOf(4, limit)) add(it, 1) }
        listOf(Carrier.CHINA_MOBILE, Carrier.CHINA_UNICOM, Carrier.CHINA_TELECOM).forEach { type ->
            candidates.firstOrNull { carrier(it) == type }?.let { add(it, 2) }
        }
        candidates.forEach { add(it, 2) }
        return selected.take(limit)
    }

    internal fun hostOf(url: String): String = try {
        URI(url).host.orEmpty().ifBlank {
            url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        }
    } catch (_: Exception) {
        ""
    }

    private fun isPrivateHost(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost" || h == "::" || h == "::1" ||
            h.startsWith("fc") || h.startsWith("fd") ||
            h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb") || h.startsWith("ff")) return true
        if (h.startsWith("10.") || h.startsWith("192.168.")) return true
        val parts = h.split('.')
        if (parts.size == 4) {
            val first = parts[0].toIntOrNull() ?: return false
            val second = parts[1].toIntOrNull() ?: return false
            if (first == 172 && second in 16..31) return true
            if (first == 100 && second in 64..127) return true
            if (first == 169 && second == 254) return true
            if (first == 198 && second in 18..19) return true
            if (first == 0 || first == 127 || first >= 224) return true
            if (h.startsWith("192.0.2.") || h.startsWith("198.51.100.") ||
                h.startsWith("203.0.113.")) return true
        }
        return false
    }
}
