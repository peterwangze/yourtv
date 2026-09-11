package com.horsenma.yourtv.playback.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEvidenceTest {
    @Test
    fun delayedFramesCannotConfirmRapidABASelection() {
        // Same URL A is selected twice but every prepare has a unique media ID.
        assertFalse(PlaybackEvidence.acceptsFrame("3:3", "1:1", 100, 110))
        assertFalse(PlaybackEvidence.acceptsFrame("3:3", "2:2", 100, 110))
        // Even a notification attributed to the current timeline is too old.
        assertFalse(PlaybackEvidence.acceptsFrame("3:3", "3:3", 100, 99))
        assertTrue(PlaybackEvidence.acceptsFrame("3:3", "3:3", 100, 110))
    }

    @Test
    fun advancingAudioWithoutVideoFrameStillTimesOut() {
        assertTrue(PlaybackEvidence.firstFrameExpired(false, true, true, 8_000, 8_000, false))
        assertTrue(PlaybackEvidence.firstFrameExpired(false, true, true, 2_000, 8_000, true))
        assertFalse(PlaybackEvidence.firstFrameExpired(true, true, true, 8_000, 8_000, true))
    }

    @Test
    fun offlineOrPausedPlaybackDoesNotSpendFirstFrameRetries() {
        assertFalse(PlaybackEvidence.firstFrameExpired(false, false, true, 60_000, 8_000, true))
        assertFalse(PlaybackEvidence.firstFrameExpired(false, true, false, 60_000, 8_000, true))
    }
}
