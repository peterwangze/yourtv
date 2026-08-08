package com.horsenma.yourtv.models

/**
 * Strict parser for the metadata portion of an #EXTINF line.
 *
 * A surprising number of public M3U feeds contain empty attributes and use the
 * attribute text as the label after the comma (for example
 * `tvg-id="" tvg-name=""`).  Treating that label as a channel name is what
 * produced entries such as `tvg-id="" tvg-...` in the TV list.  Keep this parser
 * independent from Android so it can be covered by JVM tests.
 */
object ChannelMetadataParser {

    data class Parsed(
        val name: String,
        val title: String,
        val logo: String,
        val group: String,
        val number: Int
    )

    private val attributePattern = Regex(
        """([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))"""
    )
    private val metadataLabelPattern = Regex("(?i)^(?:tvg|group|radio|channel|logo)[-_].*")
    private val noisePattern = Regex(
        "(?i)(广告|购物|促销|招商|营销|热线|返利|点播|回放|预告|轮播|测试|样片|更新时间|历年春晚|" +
            "\\d{4}年春晚|" +
            "advert|advertisement|promotion|promo|commercial|vod|timeshift|catchup|sample|test)"
    )

    fun parse(extInf: String): Parsed? {
        if (!extInf.trimStart().startsWith("#EXTINF", ignoreCase = true)) return null
        val comma = firstCommaOutsideQuotes(extInf) ?: return null
        val prefixAttributes = parseAttributes(extInf.substring(0, comma))
        var attributes = prefixAttributes
        var label = extInf.substring(comma + 1).trim()

        // Also accept feeds which put the attributes after the duration:
        // `#EXTINF:-1,tvg-id="" tvg-name="CCTV1"...,CCTV1`.
        // This is widespread in IPTV lists and was the direct cause of the
        // screenshot's `tvg-id="" tvg-...` channel names.
        val secondCommaRelative = firstCommaOutsideQuotes(label)
        if (secondCommaRelative != null) {
            val attributeText = label.substring(0, secondCommaRelative)
            val movedAttributes = parseAttributes(attributeText)
            if (movedAttributes.isNotEmpty()) {
                // If both layouts are present, the later non-empty value wins
                // (e.g. an empty pre-comma tvg-id followed by tvg-name/group).
                attributes = prefixAttributes + movedAttributes
                label = label.substring(secondCommaRelative + 1).trim()
            }
        }

        val candidate = sequenceOf(
            attributes["tvg-name"],
            attributes["channel-name"],
            cleanLabel(label),
            attributes["tvg-id"],
            attributes["channel-id"]
        ).map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() && !metadataLabelPattern.matches(it) }
            ?: return null

        val group = attributes["group-title"].orEmpty().trim()
        val title = cleanDisplayName(candidate)
        if (title.isEmpty() || isNoise(title, group, label)) return null

        return Parsed(
            name = title,
            title = title,
            logo = attributes["tvg-logo"].orEmpty().trim(),
            group = group,
            number = attributes["tvg-chno"]?.trim()?.toIntOrNull() ?: -1
        )
    }

    /** Returns true for a URL which is clearly a VOD/ad/metadata endpoint. */
    fun isNoiseUri(uri: String): Boolean {
        val lower = uri.lowercase()
        return lower.contains("gslbmgspvod") ||
            lower.contains("depository_eos") ||
            lower.contains("timeshift") ||
            lower.contains("catchup") ||
            lower.contains("/vod/") ||
            lower.contains("/advert") ||
            lower.contains("/ad/") ||
            // 点播资源站（影视资源站 ffzy 系）
            lower.contains("ffzy") ||
            // 斗鱼/虎牙 7x24 转播循环（动画/电影循环播放，非直播频道）
            lower.contains("metshop.top") ||
            // 央视/CNTV 点播片段（人与自然/地理中国/航拍中国 等纪录短片）
            lower.contains("newcntv.qcloudcdn.com") ||
            lower.contains("cntv.lxdns.com") ||
            // 快手视频文件（历年春晚/卫视录像缓存，非实时直播）
            lower.contains("kwimgs.com")
    }

    /** Reject an obvious cross-wired CCTV URL (CCTV1 label pointing to CCTV2). */
    fun isLikelyWrongChannelUri(title: String, uri: String): Boolean {
        val titleNumber = Regex("(?i)\\bcctv[-_ ]?(\\d{1,2})\\b").find(title)?.groupValues?.get(1)
            ?: return false
        val urlNumber = Regex("(?i)cctv[-_/ ]?(\\d{1,2})(?:hd|plus|k|[./_-])").find(uri)?.groupValues?.get(1)
            ?: return false
        return titleNumber.toIntOrNull() != urlNumber.toIntOrNull()
    }

    fun isNoise(title: String, group: String = "", uri: String = ""): Boolean {
        val metadata = "$title $group".trim()
        return metadata.isEmpty() || noisePattern.containsMatchIn(metadata) ||
            metadata.count { it == '=' } >= 2 ||
            metadata.startsWith("http://", ignoreCase = true) ||
            metadata.startsWith("https://", ignoreCase = true) ||
            isNoiseUri(uri)
    }

    private fun cleanLabel(label: String): String? {
        val value = label.trim().trim(',', '，')
        if (value.isEmpty() || metadataLabelPattern.matches(value) || value.count { it == '=' } >= 2) {
            return null
        }
        return value
    }

    private fun cleanDisplayName(value: String): String {
        return value.replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', '，', ';', '；')
            .take(80)
    }

    private fun parseAttributes(value: String): Map<String, String> {
        return attributePattern.findAll(value).associate { match ->
            val key = match.groupValues[1].lowercase()
            val content = sequenceOf(match.groupValues[2], match.groupValues[3], match.groupValues[4])
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            key to content
        }
    }

    private fun firstCommaOutsideQuotes(value: String): Int? {
        var quote: Char? = null
        value.forEachIndexed { index, char ->
            when {
                (char == '\"' || char == '\'') && quote == null -> quote = char
                char == quote -> quote = null
                char == ',' && quote == null -> return index
            }
        }
        return null
    }
}
