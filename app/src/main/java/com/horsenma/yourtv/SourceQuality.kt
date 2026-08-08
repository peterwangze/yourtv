package com.horsenma.yourtv

/**
 * 线路质量评分：优先高清稳定源。
 * 实际测量分辨率缓存 > URL 关键词识别。
 */
object SourceQuality {

    // 词边界匹配，避免误伤：jiangsuhd（高清）、64k.m3u8（音频流）等子串
    private val RE_8K = Regex("(^|[^a-z0-9])8k([^a-z0-9]|$)")
    private val RE_4K = Regex("(^|[^a-z0-9])4k([^a-z0-9]|$)")
    private val RE_UHD = Regex("(^|[^a-z])uhd([^a-z]|$)")
    private val RE_HD = Regex("(^|[^a-z])hd")

    /** 根据 URL 关键词估算清晰度分（0-100） */
    fun score(url: String): Int {
        val u = url.lowercase()
        return when {
            RE_8K.containsMatchIn(u) -> 100
            RE_4K.containsMatchIn(u) || RE_UHD.containsMatchIn(u) || "2160" in u -> 95
            // 咪咕运营商级 HLS（gslbmgsplive/hlsztemgsplive/aikan.miguvideo.com），
            // 路径中的 /2000/、/2500/、/3000/ 为码率档位，实测多为 1080p+ h265
            "miguvideo.com" in u || "migu" in u -> 90
            // 百视通 bestv / APTV 8M 卫视源，实测 1080p 高码率
            "bestv" in u || "aptvapp.com" in u -> 88
            "1080" in u || "fhd" in u -> 85
            "720" in u || RE_HD.containsMatchIn(u) -> 70
            "576" in u || "480" in u || "sd" in u -> 50
            "360" in u -> 35
            else -> 55
        }
    }

    /** 根据实际测量分辨率（如 "1920x1080"）打分，优先级高于 URL 关键词 */
    fun scoreWithResolution(url: String, measured: String?, title: String? = null): Int {
        if (measured != null) {
            val width = measured.substringBefore("x").trim().toIntOrNull()
            if (width != null && width > 0) {
                return when {
                    width >= 2560 -> 100
                    width >= 1920 -> 90
                    width >= 1280 -> 75
                    width >= 960 -> 60
                    width >= 640 -> 45
                    else -> 30
                }
            }
        }
        val urlScore = score(url)
        if (title != null) {
            val t = title.lowercase()
            return when {
                "4k" in t || "uhd" in t || "2160" in t -> maxOf(urlScore, 95)
                "超清" in t -> maxOf(urlScore, 88)
                "高清" in t -> maxOf(urlScore, 80)
                else -> urlScore
            }
        }
        return urlScore
    }
}
