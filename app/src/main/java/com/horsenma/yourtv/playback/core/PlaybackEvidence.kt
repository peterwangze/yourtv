package com.horsenma.yourtv.playback.core

/** Reject delayed renderer notifications even if delivered to a new listener. */
internal object PlaybackEvidence {
    fun acceptsFrame(expectedMediaId: String, eventMediaId: String, requestedAt: Long, renderedAt: Long): Boolean =
        expectedMediaId == eventMediaId && renderedAt >= requestedAt

    // READY/isPlaying are deliberately absent: an advancing audio clock cannot
    // prove that a video's first frame was displayed.
    fun firstFrameExpired(
        started: Boolean, networkAvailable: Boolean, playWhenReady: Boolean,
        elapsedMs: Long, attemptTimeoutMs: Long, totalBudgetExpired: Boolean,
    ): Boolean = !started && networkAvailable && playWhenReady &&
        (elapsedMs >= attemptTimeoutMs || totalBudgetExpired)
}
