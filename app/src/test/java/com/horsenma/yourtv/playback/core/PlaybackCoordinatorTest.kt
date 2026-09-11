package com.horsenma.yourtv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {
    private fun fail(c: PlaybackCoordinator, autoSwitch: Boolean = true): PlaybackAction {
        val s = c.currentSession!!
        return c.onError(s.sessionId, s.attemptId, s.networkGeneration, PlaybackFailure.TIMEOUT,
            autoSwitchEnabled = autoSwitch)
    }

    private fun frame(c: PlaybackCoordinator) {
        val s = c.currentSession!!
        c.onFirstFrame(s.sessionId, s.attemptId, s.networkGeneration)
    }

    private fun stable(c: PlaybackCoordinator) {
        val s = c.currentSession!!
        c.onStablePlayback(s.sessionId, s.attemptId, s.networkGeneration)
    }

    @Test
    fun slowStartupTriesBackupBeforeTotalDeadline() {
        val clock = TestClock()
        val c = PlaybackCoordinator({ clock.value })
        c.play("tv", listOf("dead-a", "good-b", "c"))
        clock.advance(8_000)
        assertEquals("good-b", (fail(c) as PlaybackAction.SwitchLine).lineId)
        clock.advance(1_000)
        frame(c)
        assertEquals(PlaybackState.PLAYING, c.currentSession!!.state)
    }

    @Test
    fun slowRecoveryReservesRemainingTimeForBackup() {
        val clock = TestClock()
        val c = PlaybackCoordinator({ clock.value })
        c.play("tv", listOf("a", "b"))
        frame(c)
        assertTrue(fail(c) is PlaybackAction.RetrySameLine)
        clock.advance(8_000)
        assertEquals("b", (fail(c) as PlaybackAction.SwitchLine).lineId)
        clock.advance(1_000)
        frame(c)
        assertEquals(PlaybackState.PLAYING, c.currentSession!!.state)
    }

    @Test
    fun disabledAutoSwitchStillHonorsStartupAndRecoveryDeadlines() {
        val clock = TestClock()
        val c = PlaybackCoordinator({ clock.value })
        c.play("tv", listOf("a", "b"))
        clock.advance(20_001)
        assertTrue(fail(c, false) is PlaybackAction.ShowError)
        c.play("tv", listOf("a", "b"))
        frame(c)
        assertTrue(fail(c, false) is PlaybackAction.RetrySameLine)
        clock.advance(12_001)
        assertTrue(fail(c, false) is PlaybackAction.ShowError)
    }

    @Test
    fun manualSelectionCanReturnToPreviousLineAfterStablePlayback() {
        val clock = TestClock()
        val c = PlaybackCoordinator({ clock.value })
        c.play("tv", listOf("a", "b"))
        c.beginAttempt(c.currentSession!!.sessionId, "b")
        frame(c)
        clock.advance(60_000)
        stable(c)
        fail(c)
        fail(c)
        assertEquals("a", (fail(c) as PlaybackAction.SwitchLine).lineId)
    }

    @Test
    fun rollingSwitchLimitAllowsSameLineRetriesButNoFurtherSwitch() {
        val clock = TestClock()
        val c = PlaybackCoordinator({ clock.value })
        c.play("tv", listOf("a", "b", "c"))
        frame(c)
        repeat(2) {
            fail(c)
            fail(c)
            assertTrue(fail(c) is PlaybackAction.SwitchLine)
            frame(c)
            clock.advance(60_000)
            stable(c)
        }
        assertEquals(2, c.currentSession!!.recoverySwitches)
        assertTrue(fail(c) is PlaybackAction.RetrySameLine)
        assertTrue(fail(c) is PlaybackAction.RetrySameLine)
        assertTrue(fail(c) is PlaybackAction.ShowError)
    }

    @Test
    fun offlineMinuteDoesNotConsumeReconnectedStartupBudget() {
        val clock = TestClock()
        val c = PlaybackCoordinator({ clock.value })
        c.play("tv", listOf("a", "b"))
        c.onNetworkChanged(false)
        clock.advance(60_000)
        c.onNetworkChanged(true)
        assertTrue(fail(c) is PlaybackAction.RetrySameLine)
    }

    @Test
    fun explicitAttemptAfterTimeoutGetsFreshBudgetAndRejectsOldFrames() {
        val clock = TestClock()
        val c = PlaybackCoordinator({ clock.value })
        val old = c.play("tv", listOf("a", "b")) as PlaybackAction.Prepare
        clock.advance(20_001)
        fail(c)
        c.beginAttempt(old.sessionId, "b")
        assertFalse(c.accepts(old.sessionId, old.attemptId, 0))
        assertTrue(fail(c) is PlaybackAction.RetrySameLine)
        assertEquals(setOf("b"), c.currentSession!!.attemptedLineIds)
    }

    @Test
    fun bufferedPlaybackSurvivesRouteLossWithoutNewPrepare() {
        val clock = TestClock()
        val c = PlaybackCoordinator({ clock.value })
        val initial = c.play("tv", listOf("a")) as PlaybackAction.Prepare
        frame(c)
        assertTrue(c.onNetworkChanged(false, preservePlayback = true) is PlaybackAction.WaitForNetwork)
        clock.advance(1_000)
        assertEquals(PlaybackAction.None, c.onNetworkChanged(true, preservePlayback = true))
        assertEquals(initial.attemptId, c.currentSession!!.attemptId)
        assertEquals(PlaybackState.PLAYING, c.currentSession!!.state)
        assertFalse(c.accepts(initial.sessionId, initial.attemptId, 0))
        assertTrue(c.accepts(initial.sessionId, initial.attemptId, 2))
    }

    @Test
    fun longBackgroundPauseGetsFreshBudgetAndNetworkCannotResumeIt() {
        val clock = TestClock()
        val c = PlaybackCoordinator({ clock.value })
        c.play("tv", listOf("a"))
        c.suspend()
        clock.advance(60_000)
        assertEquals(PlaybackAction.None, c.onNetworkChanged(true, preservePlayback = true))
        assertEquals(PlaybackState.SUSPENDED, c.currentSession!!.state)
        assertTrue(c.resume() is PlaybackAction.Prepare)
        assertTrue(fail(c) is PlaybackAction.RetrySameLine)
    }

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
