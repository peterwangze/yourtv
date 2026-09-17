package com.horsenma.yourtv

/** Shared ordering for the catalogue, first prepare and recovery candidates. */
object SourcePreference {
    data class Candidate(
        val url: String,
        val resolution: String? = null,
        val health: Int = 1,
        val stable: Boolean = false,
        val startupMs: Long? = null,
        val compatibility: Int = 25,
        val sourceWeight: Int = 60,
    )

    fun rank(candidates: List<Candidate>): List<String> = candidates.distinctBy { it.url }
        .sortedWith(compareByDescending<Candidate> { it.health > 0 }
            .thenByDescending { SourceQuality.preferenceTier(it.url, it.resolution) }
            .thenByDescending { candidate ->
                SourceQuality.resolution(candidate.resolution)?.let { size ->
                    if (size.fullHd) 0L else size.width.toLong() * size.height
                } ?: 0L
            }
            // 1080 is the target, not a requirement to force 4K through a weak route.
            .thenByDescending { it.stable && it.health >= 3 }
            .thenByDescending { it.health }
            .thenBy { it.startupMs ?: Long.MAX_VALUE }
            .thenByDescending { it.compatibility }
            .thenByDescending { it.sourceWeight }
            .thenBy { it.url })
        .map { it.url }
}
