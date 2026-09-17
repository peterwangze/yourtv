package com.horsenma.yourtv

import android.content.Context
import org.json.JSONObject

/** Bundled measurements describe media dimensions only, never this user's network speed. */
object SourceSelection {
    @Volatile private var bundled: Map<String, String> = emptyMap()

    fun loadMeasurements(context: Context) {
        bundled = runCatching {
            val json = JSONObject(context.assets.open("bundled_quality.json").bufferedReader().use { it.readText() })
            val timestamp = json.optLong("measuredAtMs")
            if (System.currentTimeMillis() - timestamp !in 0..30L * 24 * 60 * 60 * 1000) return@runCatching emptyMap()
            val lines = json.getJSONObject("resolutions")
            lines.keys().asSequence().associateWith { lines.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun resolution(url: String): String? = SP.getResolutionCache(url) ?: bundled[url]

    fun rank(urls: List<String>, stableUrls: Set<String>, weight: (String) -> Int = { 60 }): List<String> =
        SourcePreference.rank(urls.map { url -> SourcePreference.Candidate(
            url = url, resolution = resolution(url), health = LineHealth.healthRank(url),
            stable = url in stableUrls && LineHealth.isStable(url), startupMs = LineHealth.playbackLatency(url),
            compatibility = SourceNetworkPolicy.compatibilityScore(url), sourceWeight = weight(url),
        ) })
}
