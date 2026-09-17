package com.horsenma.yourtv.playback.core

import org.junit.Assert.*
import org.junit.Test

class QualityFallbackTest {
    private var now = 0L
    private val coordinator = PlaybackCoordinator({ now })
    private fun error(auto: Boolean = true): PlaybackAction {
        val s = coordinator.currentSession!!
        return coordinator.onError(s.sessionId, s.attemptId, s.networkGeneration,
            PlaybackFailure.TIMEOUT, autoSwitchEnabled = auto)
    }

    @Test fun `HD startup deadline permits one SD attempt then terminates`() {
        coordinator.play("c", listOf("hd1", "hd2", "sd"), "sd")
        now = 20_001
        assertEquals("sd", (error() as PlaybackAction.SwitchLine).lineId)
        now += 8_001
        assertTrue(error() is PlaybackAction.ShowError)
        assertEquals(PlaybackState.RECOVERABLE_ERROR, coordinator.currentSession!!.state)
    }
    @Test fun `manual line selection does not trigger compatibility override`() {
        coordinator.play("c", listOf("hd", "sd"), "sd")
        coordinator.beginAttempt(coordinator.currentSession!!.sessionId, "hd")
        now = 20_001
        assertTrue(error() is PlaybackAction.ShowError)
    }
    @Test fun `all shortlisted HD lines are attempted before SD even after normal deadline`() {
        val hd = (1..5).map { "hd$it" }
        coordinator.play("c", hd + "sd", "sd", hd.toSet())
        hd.drop(1).forEach { next ->
            now += 8_001
            assertEquals(next, (error() as PlaybackAction.SwitchLine).lineId)
        }
        now += 8_001
        assertEquals("sd", (error() as PlaybackAction.SwitchLine).lineId)
        now += 8_001
        assertTrue(error() is PlaybackAction.ShowError)
    }
    @Test fun `disabled auto switch is respected`() {
        coordinator.play("c", listOf("hd", "sd"), "sd")
        now = 20_001
        assertTrue(error(false) is PlaybackAction.ShowError)
    }
    @Test fun `previous channel fallback cannot leak into next channel`() {
        coordinator.play("a", listOf("hd", "sd"), "sd")
        coordinator.play("b", listOf("bhd"))
        now = 20_001
        assertTrue(error() is PlaybackAction.ShowError)
    }
}
