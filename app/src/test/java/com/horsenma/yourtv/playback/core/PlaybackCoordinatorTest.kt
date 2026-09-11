package com.horsenma.yourtv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {
    private class TestClock(var value: Long = 0L) {
        fun advance(ms: Long) {
            value += ms
        }
    }

    @Test
    fun staleFirstFrameCannotRevivePreviousSession() {
        val clock = TestClock()
        val coordinator = PlaybackCoordinator({ clock.value })
        val first = coordinator.play("cctv1", listOf("line-a")) as PlaybackAction.Prepare
        coordinator.play("cctv5", listOf("line-b"))

        assertEquals(PlaybackAction.None, coordinator.onFirstFrame(first.sessionId, first.attemptId, 0L))
        assertEquals("cctv5", coordinator.currentSession?.channelId)
        assertFalse(coordinator.currentSession?.hasFirstFrame == true)
    }

    @Test
    fun startupRetriesSameLineThenMovesToTwoOtherLines() {
        val clock = TestClock()
        val coordinator = PlaybackCoordinator({ clock.value })
        val first = coordinator.play("cctv1", listOf("a", "b", "c")) as PlaybackAction.Prepare

        val retry1 = coordinator.onError(first.sessionId, first.attemptId, 0L, PlaybackFailure.TIMEOUT)
        assertTrue(retry1 is PlaybackAction.RetrySameLine)
        val firstRetry = retry1 as PlaybackAction.RetrySameLine
        val retry2 = coordinator.onError(firstRetry.sessionId, firstRetry.attemptId, 0L, PlaybackFailure.TIMEOUT)
        assertTrue(retry2 is PlaybackAction.RetrySameLine)
        val secondRetry = retry2 as PlaybackAction.RetrySameLine
        val next = coordinator.onError(secondRetry.sessionId, secondRetry.attemptId, 0L, PlaybackFailure.TIMEOUT)
        assertTrue(next is PlaybackAction.SwitchLine)
        assertEquals("b", (next as PlaybackAction.SwitchLine).lineId)
    }

    @Test
    fun startupBudgetEndsWithActionableError() {
        val clock = TestClock()
        val coordinator = PlaybackCoordinator({ clock.value })
        val first = coordinator.play("cctv1", listOf("a")) as PlaybackAction.Prepare
        clock.advance(20_001)

        val result = coordinator.onError(first.sessionId, first.attemptId, 0L, PlaybackFailure.TIMEOUT)
        assertTrue(result is PlaybackAction.ShowError)
        assertEquals(PlaybackState.RECOVERABLE_ERROR, coordinator.currentSession?.state)
    }

    @Test
    fun recoveryBudgetEndsWithActionableError() {
        val clock = TestClock()
        val coordinator = PlaybackCoordinator({ clock.value })
        val first = coordinator.play("cctv1", listOf("a", "b")) as PlaybackAction.Prepare
        coordinator.onFirstFrame(first.sessionId, first.attemptId, 0L)
        val retry = coordinator.onError(first.sessionId, first.attemptId, 0L, PlaybackFailure.TIMEOUT)
            as PlaybackAction.RetrySameLine
        clock.advance(12_001L)

        val result = coordinator.onError(retry.sessionId, retry.attemptId, 0L, PlaybackFailure.TIMEOUT)

        assertTrue(result is PlaybackAction.ShowError)
        assertEquals(PlaybackState.RECOVERABLE_ERROR, coordinator.currentSession?.state)
    }

    @Test
    fun networkGenerationInvalidatesOldCallbacksAndCanResume() {
        val clock = TestClock()
        val coordinator = PlaybackCoordinator({ clock.value })
        val first = coordinator.play("cctv1", listOf("a")) as PlaybackAction.Prepare
        coordinator.onNetworkChanged(available = false)
        assertFalse(coordinator.accepts(first.sessionId, first.attemptId, 0L))

        val resumed = coordinator.onNetworkChanged(available = true)
        assertTrue(resumed is PlaybackAction.Prepare)
        val action = resumed as PlaybackAction.Prepare
        assertTrue(coordinator.accepts(action.sessionId, action.attemptId, coordinator.currentSession!!.networkGeneration))
    }

    @Test
    fun networkChangeDuringPrepareCreatesFreshAttempt() {
        val coordinator = PlaybackCoordinator({ 0L })
        val first = coordinator.play("cctv1", listOf("a")) as PlaybackAction.Prepare

        val reconnected = coordinator.onNetworkChanged(available = true) as PlaybackAction.Prepare

        assertTrue(reconnected.attemptId != first.attemptId)
        assertFalse(coordinator.accepts(first.sessionId, first.attemptId, 0L))
        assertTrue(coordinator.accepts(reconnected.sessionId, reconnected.attemptId, 1L))
    }

    @Test
    fun networkChangeCannotReviveSuspendedOrTerminalSession() {
        val suspended = PlaybackCoordinator({ 0L })
        suspended.play("cctv1", listOf("a"))
        suspended.suspend()
        assertEquals(PlaybackAction.None, suspended.onNetworkChanged(available = true))
        assertEquals(PlaybackState.SUSPENDED, suspended.currentSession?.state)

        val terminal = PlaybackCoordinator({ 0L })
        val first = terminal.play("cctv1", listOf("a")) as PlaybackAction.Prepare
        val retry1 = terminal.onError(first.sessionId, first.attemptId, 0L, PlaybackFailure.TIMEOUT)
            as PlaybackAction.RetrySameLine
        val retry2 = terminal.onError(retry1.sessionId, retry1.attemptId, 0L, PlaybackFailure.TIMEOUT)
            as PlaybackAction.RetrySameLine
        terminal.onError(retry2.sessionId, retry2.attemptId, 0L, PlaybackFailure.TIMEOUT)

        assertEquals(PlaybackAction.None, terminal.onNetworkChanged(available = true))
        assertEquals(PlaybackState.RECOVERABLE_ERROR, terminal.currentSession?.state)
    }

    @Test
    fun noLinesProducesErrorWithoutTryingAChannel() {
        val coordinator = PlaybackCoordinator({ 0L })
        val result = coordinator.play("empty", emptyList())
        assertTrue(result is PlaybackAction.ShowError)
        assertEquals(PlaybackState.RECOVERABLE_ERROR, coordinator.currentSession?.state)
    }

    @Test
    fun disablingAutoSwitchKeepsRecoveryOnTheCurrentLine() {
        val clock = TestClock()
        val coordinator = PlaybackCoordinator({ clock.value })
        val first = coordinator.play("cctv1", listOf("a", "b")) as PlaybackAction.Prepare
        coordinator.onFirstFrame(first.sessionId, first.attemptId, 0L)

        val retry = coordinator.onError(
            first.sessionId,
            first.attemptId,
            0L,
            PlaybackFailure.NETWORK,
            autoSwitchEnabled = false,
        )
        assertTrue(retry is PlaybackAction.RetrySameLine)
        assertEquals("a", (retry as PlaybackAction.RetrySameLine).lineId)
    }

    @Test
    fun disablingAutoSwitchStopsAfterBoundedSameLineRetries() {
        val coordinator = PlaybackCoordinator({ 0L })
        val first = coordinator.play("cctv1", listOf("a", "b")) as PlaybackAction.Prepare
        val retry1 = coordinator.onError(
            first.sessionId,
            first.attemptId,
            0L,
            PlaybackFailure.NETWORK,
            autoSwitchEnabled = false,
        ) as PlaybackAction.RetrySameLine
        val retry2 = coordinator.onError(
            retry1.sessionId,
            retry1.attemptId,
            0L,
            PlaybackFailure.NETWORK,
            autoSwitchEnabled = false,
        ) as PlaybackAction.RetrySameLine

        val result = coordinator.onError(
            retry2.sessionId,
            retry2.attemptId,
            0L,
            PlaybackFailure.NETWORK,
            autoSwitchEnabled = false,
        )

        assertTrue(result is PlaybackAction.ShowError)
        assertEquals(PlaybackState.RECOVERABLE_ERROR, coordinator.currentSession?.state)
        assertEquals("a", coordinator.currentSession?.selectedLineId)
    }

    @Test
    fun resumeCreatesFreshAttemptAndRejectsSuspendedCallbacks() {
        val coordinator = PlaybackCoordinator({ 0L })
        val first = coordinator.play("cctv1", listOf("a")) as PlaybackAction.Prepare

        coordinator.suspend()
        assertFalse(coordinator.accepts(first.sessionId, first.attemptId, 0L))

        val resumed = coordinator.resume() as PlaybackAction.Prepare
        assertTrue(resumed.attemptId != first.attemptId)
        assertFalse(coordinator.accepts(first.sessionId, first.attemptId, 0L))
        assertTrue(coordinator.accepts(resumed.sessionId, resumed.attemptId, 0L))
    }

    @Test
    fun missingNextLineTransitionsToRecoverableError() {
        val coordinator = PlaybackCoordinator({ 0L })
        val first = coordinator.play("cctv1", listOf("a")) as PlaybackAction.Prepare
        val retry1 = coordinator.onError(first.sessionId, first.attemptId, 0L, PlaybackFailure.TIMEOUT)
            as PlaybackAction.RetrySameLine
        val retry2 = coordinator.onError(retry1.sessionId, retry1.attemptId, 0L, PlaybackFailure.TIMEOUT)
            as PlaybackAction.RetrySameLine

        val result = coordinator.onError(retry2.sessionId, retry2.attemptId, 0L, PlaybackFailure.TIMEOUT)

        assertTrue(result is PlaybackAction.ShowError)
        assertEquals(PlaybackState.RECOVERABLE_ERROR, coordinator.currentSession?.state)
    }

    @Test
    fun readyBeforeFirstFrameDoesNotClaimPlayback() {
        val coordinator = PlaybackCoordinator({ 0L })
        val first = coordinator.play("cctv1", listOf("a")) as PlaybackAction.Prepare
        val retry = coordinator.onError(first.sessionId, first.attemptId, 0L, PlaybackFailure.TIMEOUT)
            as PlaybackAction.RetrySameLine

        coordinator.onRecovered(retry.sessionId, retry.attemptId, 0L)
        assertEquals(PlaybackState.PREPARING, coordinator.currentSession?.state)
        assertFalse(coordinator.currentSession?.hasFirstFrame == true)

        coordinator.onFirstFrame(retry.sessionId, retry.attemptId, 0L)
        assertEquals(PlaybackState.PLAYING, coordinator.currentSession?.state)
    }

    @Test
    fun stablePlaybackStartsANewEpisodeButKeepsRollingSwitchCount() {
        val clock = TestClock()
        val coordinator = PlaybackCoordinator({ clock.value })
        val first = coordinator.play("cctv1", listOf("a", "b", "c")) as PlaybackAction.Prepare
        coordinator.onFirstFrame(first.sessionId, first.attemptId, 0L)

        val retry1 = coordinator.onError(first.sessionId, first.attemptId, 0L, PlaybackFailure.NETWORK)
            as PlaybackAction.RetrySameLine
        val retry2 = coordinator.onError(retry1.sessionId, retry1.attemptId, 0L, PlaybackFailure.NETWORK)
            as PlaybackAction.RetrySameLine
        val switched = coordinator.onError(retry2.sessionId, retry2.attemptId, 0L, PlaybackFailure.NETWORK)
            as PlaybackAction.SwitchLine
        coordinator.onFirstFrame(switched.sessionId, switched.attemptId, 0L)

        clock.advance(60_000L)
        coordinator.onStablePlayback(switched.sessionId, switched.attemptId, 0L)

        assertEquals(setOf("b"), coordinator.currentSession?.attemptedLineIds)
        assertEquals(1, coordinator.currentSession?.recoverySwitches)
        assertEquals(null, coordinator.currentSession?.recoveryStartedAtElapsedMs)
    }
}
