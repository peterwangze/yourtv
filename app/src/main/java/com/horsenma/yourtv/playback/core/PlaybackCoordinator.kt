package com.horsenma.yourtv.playback.core

/**
 * Serial, UI-independent policy for one playback session.
 *
 * The Android player remains responsible for Media3 calls. This class owns
 * intent identity and recovery decisions, so stale player callbacks cannot
 * start a new line or mutate the current session.
 */
class PlaybackCoordinator(
    private val nowElapsedMs: () -> Long,
    private val budget: RecoveryBudget = RecoveryBudget(),
) {
    private var nextSessionId = 0L
    private var nextAttemptId = 0L
    private var networkGeneration = 0L
    private var lines: List<String> = emptyList()
    private var lineCursor = 0
    private var session: PlaybackSession? = null

    val currentSession: PlaybackSession?
        get() = session

    val currentNetworkGeneration: Long
        get() = networkGeneration

    fun play(channelId: String, lineIds: List<String>): PlaybackAction {
        val candidates = lineIds.filter(String::isNotBlank).distinct()
        if (candidates.isEmpty()) {
            nextSessionId += 1
            session = PlaybackSession(
                sessionId = nextSessionId,
                channelId = channelId,
                networkGeneration = networkGeneration,
                attemptId = 0L,
                selectedLineId = null,
                attemptedLineIds = emptySet(),
                startedAtElapsedMs = nowElapsedMs(),
                recoveryStartedAtElapsedMs = null,
                recoveryWindowStartedAtElapsedMs = null,
                recoverySwitches = 0,
                sameLineRetries = 0,
                hasFirstFrame = false,
                intent = PlaybackIntent.PLAY,
                state = PlaybackState.RECOVERABLE_ERROR,
            )
            return PlaybackAction.ShowError(nextSessionId, channelId, PlaybackFailure.NOT_FOUND)
        }

        nextSessionId += 1
        lines = candidates
        lineCursor = 0
        val lineId = lines.first()
        val attemptId = nextAttemptId++
        session = PlaybackSession(
            sessionId = nextSessionId,
            channelId = channelId,
            networkGeneration = networkGeneration,
            attemptId = attemptId,
            selectedLineId = lineId,
            attemptedLineIds = setOf(lineId),
            startedAtElapsedMs = nowElapsedMs(),
            recoveryStartedAtElapsedMs = null,
            recoveryWindowStartedAtElapsedMs = null,
            recoverySwitches = 0,
            sameLineRetries = 0,
            hasFirstFrame = false,
            intent = PlaybackIntent.PLAY,
            state = PlaybackState.PREPARING,
        )
        return PlaybackAction.Prepare(nextSessionId, attemptId, lineId)
    }

    fun stop() {
        session = null
        lines = emptyList()
        lineCursor = 0
    }

    /** Starts a new Media3 attempt without changing the user's channel intent. */
    fun beginAttempt(sessionId: Long, lineId: String): PlaybackAction {
        val current = session ?: return PlaybackAction.None
        if (current.sessionId != sessionId || lineId.isBlank()) return PlaybackAction.None
        // A refreshed channel catalogue may expose a newly selected endpoint.
        if (lineId !in lines) lines = lines + lineId
        lineCursor = lines.indexOf(lineId)
        val attemptId = nextAttemptId++
        val updated = current.copy(
            attemptId = attemptId,
            selectedLineId = lineId,
            attemptedLineIds = setOf(lineId),
            startedAtElapsedMs = nowElapsedMs(),
            recoveryStartedAtElapsedMs = null,
            hasFirstFrame = false,
            intent = PlaybackIntent.PLAY,
            sameLineRetries = 0,
            state = PlaybackState.PREPARING,
        )
        session = updated
        return PlaybackAction.Prepare(updated.sessionId, attemptId, lineId)
    }

    fun suspend(): PlaybackAction {
        val current = session ?: return PlaybackAction.None
        if (current.intent == PlaybackIntent.PAUSE || current.state == PlaybackState.RECOVERABLE_ERROR) {
            return PlaybackAction.None
        }
        session = current.copy(intent = PlaybackIntent.PAUSE, state = PlaybackState.SUSPENDED)
        return PlaybackAction.None
    }

    fun resume(): PlaybackAction {
        val current = session ?: return PlaybackAction.None
        if (current.state != PlaybackState.SUSPENDED) return PlaybackAction.None
        val lineId = current.selectedLineId ?: return PlaybackAction.None
        val attemptId = nextAttemptId++
        val resumed = current.copy(
            attemptId = attemptId,
            selectedLineId = lineId,
            attemptedLineIds = setOf(lineId),
            intent = PlaybackIntent.PLAY,
            state = PlaybackState.PREPARING,
            startedAtElapsedMs = nowElapsedMs(),
            recoveryStartedAtElapsedMs = if (current.hasFirstFrame) nowElapsedMs() else null,
            sameLineRetries = 0,
        )
        session = resumed
        return PlaybackAction.Prepare(resumed.sessionId, attemptId, lineId)
    }

    fun onFirstFrame(sessionId: Long, attemptId: Long, callbackNetworkGeneration: Long): PlaybackAction {
        val current = session ?: return PlaybackAction.None
        if (!accepts(sessionId, attemptId, callbackNetworkGeneration)) return PlaybackAction.None
        session = current.copy(hasFirstFrame = true, sameLineRetries = 0, state = PlaybackState.PLAYING)
        return PlaybackAction.None
    }

    fun onBuffering(sessionId: Long, attemptId: Long, callbackNetworkGeneration: Long): PlaybackAction {
        val current = session ?: return PlaybackAction.None
        if (!accepts(sessionId, attemptId, callbackNetworkGeneration)) return PlaybackAction.None
        if (current.state == PlaybackState.PLAYING) {
            session = current.copy(state = PlaybackState.REBUFFERING)
        }
        return PlaybackAction.None
    }

    fun onRecovered(sessionId: Long, attemptId: Long, callbackNetworkGeneration: Long): PlaybackAction {
        val current = session ?: return PlaybackAction.None
        if (!accepts(sessionId, attemptId, callbackNetworkGeneration)) return PlaybackAction.None
        if (current.hasFirstFrame &&
            (current.state == PlaybackState.REBUFFERING || current.state == PlaybackState.RECOVERING)
        ) {
            session = current.copy(state = PlaybackState.PLAYING)
        }
        return PlaybackAction.None
    }

    fun onStablePlayback(sessionId: Long, attemptId: Long, callbackNetworkGeneration: Long): PlaybackAction {
        val current = session ?: return PlaybackAction.None
        if (!accepts(sessionId, attemptId, callbackNetworkGeneration) || !current.hasFirstFrame) {
            return PlaybackAction.None
        }
        val now = nowElapsedMs()
        val windowExpired = current.recoveryWindowStartedAtElapsedMs?.let {
            now - it > budget.recoveryWindowMs
        } ?: false
        session = current.copy(
            attemptedLineIds = setOfNotNull(current.selectedLineId),
            recoveryStartedAtElapsedMs = null,
            recoveryWindowStartedAtElapsedMs = if (windowExpired) null else current.recoveryWindowStartedAtElapsedMs,
            recoverySwitches = if (windowExpired) 0 else current.recoverySwitches,
            sameLineRetries = 0,
            state = PlaybackState.PLAYING,
        )
        return PlaybackAction.None
    }

    fun onError(
        sessionId: Long,
        attemptId: Long,
        callbackNetworkGeneration: Long,
        failure: PlaybackFailure,
        networkAvailable: Boolean = true,
        autoSwitchEnabled: Boolean = true,
    ): PlaybackAction {
        val current = session ?: return PlaybackAction.None
        if (!accepts(sessionId, attemptId, callbackNetworkGeneration)) return PlaybackAction.None
        if (!networkAvailable) {
            session = current.copy(state = PlaybackState.WAITING_FOR_NETWORK)
            return PlaybackAction.WaitForNetwork(current.sessionId)
        }

        val now = nowElapsedMs()
        val elapsed = (now - current.startedAtElapsedMs).coerceAtLeast(0L)
        val inStartup = !current.hasFirstFrame
        if (inStartup && elapsed < budget.startupDeadlineMs) {
            // A complete prepare can consume eight seconds. Preserve a chance
            // for an untried endpoint instead of spending every slot on one URL.
            if (autoSwitchEnabled && elapsed >= budget.attemptTimeoutMs &&
                current.attemptedLineIds.size < budget.startupMaxAttempts &&
                lines.any { it !in current.attemptedLineIds }
            ) return switchToNext(current, recovery = false)
            if (current.sameLineRetries < budget.sameLineRetries) {
                return retrySameLine(current)
            }
            if (autoSwitchEnabled && current.attemptedLineIds.size < budget.startupMaxAttempts) {
                return switchToNext(current, recovery = false)
            }
        } else if (!inStartup) {
            val recoveryStart = current.recoveryStartedAtElapsedMs ?: now
            val recoveryElapsed = (now - recoveryStart).coerceAtLeast(0L)
            val windowExpired = current.recoveryWindowStartedAtElapsedMs?.let {
                now - it > budget.recoveryWindowMs
            } ?: true
            val switchesInWindow = if (!windowExpired) {
                current.recoverySwitches
            } else {
                0
            }
            if (recoveryElapsed < budget.recoveryDeadlineMs) {
                val canSwitch = autoSwitchEnabled && switchesInWindow < budget.recoveryMaxSwitches &&
                    lines.any { it !in current.attemptedLineIds }
                if (current.sameLineRetries < budget.sameLineRetries &&
                    !(canSwitch && recoveryElapsed >= budget.recoveryDeadlineMs - budget.attemptTimeoutMs)
                ) {
                    return retrySameLine(current, recoveryStart)
                }
                if (canSwitch) {
                    return switchToNext(
                        current = current,
                        recovery = true,
                        recoveryStart = recoveryStart,
                        recoveryWindowStart = if (windowExpired) now else current.recoveryWindowStartedAtElapsedMs,
                        recoverySwitches = switchesInWindow + 1,
                    )
                }
            }
        }

        session = current.copy(state = PlaybackState.RECOVERABLE_ERROR)
        return PlaybackAction.ShowError(current.sessionId, current.channelId, failure)
    }

    fun onNetworkChanged(available: Boolean, preservePlayback: Boolean = false): PlaybackAction {
        networkGeneration += 1
        val current = session ?: return PlaybackAction.None
        if (current.intent == PlaybackIntent.PAUSE || current.state == PlaybackState.RECOVERABLE_ERROR) {
            session = current.copy(networkGeneration = networkGeneration)
            return PlaybackAction.None
        }
        if (!available) {
            session = current.copy(networkGeneration = networkGeneration, state = PlaybackState.WAITING_FOR_NETWORK)
            return PlaybackAction.WaitForNetwork(current.sessionId)
        }
        if (preservePlayback) {
            session = current.copy(
                networkGeneration = networkGeneration,
                startedAtElapsedMs = nowElapsedMs(),
                recoveryStartedAtElapsedMs = null,
                state = if (current.hasFirstFrame) PlaybackState.PLAYING else PlaybackState.PREPARING,
            )
            return PlaybackAction.None
        }
        val lineId = current.selectedLineId ?: run {
            session = current.copy(
                networkGeneration = networkGeneration,
                state = PlaybackState.RECOVERABLE_ERROR,
            )
            return PlaybackAction.ShowError(current.sessionId, current.channelId, PlaybackFailure.NETWORK)
        }
        val attemptId = nextAttemptId++
        val recoveryStart = if (current.hasFirstFrame) {
            nowElapsedMs()
        } else {
            current.recoveryStartedAtElapsedMs
        }
        val resumed = current.copy(
            networkGeneration = networkGeneration,
            attemptId = attemptId,
            startedAtElapsedMs = nowElapsedMs(),
            sameLineRetries = 0,
            attemptedLineIds = setOf(lineId),
            recoveryStartedAtElapsedMs = recoveryStart,
            state = PlaybackState.PREPARING,
        )
        session = resumed
        return PlaybackAction.Prepare(resumed.sessionId, attemptId, lineId)
    }

    fun accepts(sessionId: Long, attemptId: Long, callbackNetworkGeneration: Long): Boolean {
        val current = session ?: return false
        return current.intent == PlaybackIntent.PLAY &&
            current.state != PlaybackState.RECOVERABLE_ERROR &&
            matches(current, sessionId, attemptId) &&
            current.networkGeneration == callbackNetworkGeneration
    }

    private fun switchToNext(
        current: PlaybackSession,
        recovery: Boolean,
        recoveryStart: Long? = null,
        recoveryWindowStart: Long? = current.recoveryWindowStartedAtElapsedMs,
        recoverySwitches: Int = current.recoverySwitches,
    ): PlaybackAction {
        val nextIndex = lines.indices.firstOrNull { it != lineCursor && lines[it] !in current.attemptedLineIds }
            ?: run {
                session = current.copy(state = PlaybackState.RECOVERABLE_ERROR)
                return PlaybackAction.ShowError(current.sessionId, current.channelId, PlaybackFailure.UNKNOWN)
            }
        lineCursor = nextIndex
        val lineId = lines[nextIndex]
        val attemptId = nextAttemptId++
        val updated = current.copy(
            attemptId = attemptId,
            selectedLineId = lineId,
            attemptedLineIds = current.attemptedLineIds + lineId,
            recoveryStartedAtElapsedMs = if (recovery) recoveryStart ?: nowElapsedMs() else null,
            recoveryWindowStartedAtElapsedMs = recoveryWindowStart,
            recoverySwitches = recoverySwitches,
            sameLineRetries = 0,
            state = PlaybackState.PREPARING,
        )
        session = updated
        return PlaybackAction.SwitchLine(updated.sessionId, attemptId, lineId)
    }

    private fun retrySameLine(current: PlaybackSession, recoveryStart: Long? = null): PlaybackAction {
        val attemptId = nextAttemptId++
        val updated = current.copy(
            attemptId = attemptId,
            recoveryStartedAtElapsedMs = recoveryStart,
            sameLineRetries = current.sameLineRetries + 1,
            state = PlaybackState.PREPARING,
        )
        session = updated
        return PlaybackAction.RetrySameLine(updated.sessionId, attemptId, updated.selectedLineId!!)
    }

    private fun matches(current: PlaybackSession, sessionId: Long, attemptId: Long): Boolean =
        current.sessionId == sessionId && current.attemptId == attemptId
}
