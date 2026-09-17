package com.horsenma.yourtv

/**
 * 线路质量评分：优先高清稳定源。
 * 实际测量分辨率缓存 > URL 关键词识别。
 */
object SourceQuality {
    data class Resolution(val width: Int, val height: Int) {
        val fullHd: Boolean get() = width >= 1920 && height >= 1080
    }

    fun resolution(value: String?): Resolution? {
        val parts = value?.lowercase()?.split('x') ?: return null
        if (parts.size != 2) return null
        val width = parts[0].trim().toIntOrNull() ?: return null
        val height = parts[1].trim().toIntOrNull() ?: return null
        return if (width > 0 && height > 0) Resolution(width, height) else null
    }

    /** Measured 1080 beats every name/domain heuristic; 1920x540 is not 1080. */
    fun preferenceTier(url: String, measured: String?): Int {
        resolution(measured)?.let { return if (it.fullHd) 3 else 0 }
        val path = url.substringBefore('?').lowercase()
        return if (Regex("(^|[^0-9])(1080|2160)(p|[^0-9]|$)").containsMatchIn(path) ||
            RE_4K.containsMatchIn(path) || RE_8K.containsMatchIn(path)) 2 else 1
    }

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
            // 芒果TV 官方直播（湖南卫视4K 等 qing.mgtv.com）
            "mgtv.com" in u || "mgtv" in u -> 88
            // 百视通 bestv / APTV 8M 卫视源，实测 1080p 高码率
            "bestv" in u || "aptvapp.com" in u || "kan0512.com" in u -> 88
            // 浙江广电官方流（cztv 蓝天下/云平台，多为 1080p 以上）
            "cztv" in u || "cztvcloud" in u -> 87
            // 运营商 OTT 直播（移动 ottrrs/dbiptv、电信 dxhmt、广东移动 gmcc、华数 mobaibox）
            "chinamobile.com" in u || "dxhmt" in u || "gmcc.net" in u || "mobaibox.com" in u -> 85
            "1080" in u || "fhd" in u -> 85
            "720" in u || RE_HD.containsMatchIn(u) -> 70
            "576" in u || "480" in u || "sd" in u -> 50
            "360" in u -> 35
            else -> 55
        }
    }

    /** 根据实际测量分辨率（如 "1920x1080"）打分，优先级高于 URL 关键词 */
    fun scoreWithResolution(url: String, measured: String?, title: String? = null): Int {
        resolution(measured)?.let { size ->
                return when {
                    size.width >= 3840 && size.height >= 2160 -> 100
                    size.fullHd -> 90
                    size.width >= 1280 && size.height >= 720 -> 75
                    size.width >= 960 && size.height >= 540 -> 60
                    size.width >= 640 && size.height >= 360 -> 45
                    else -> 30
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
