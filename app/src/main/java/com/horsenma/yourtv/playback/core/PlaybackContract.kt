package com.horsenma.yourtv.playback.core

/** High-level lifecycle of one user playback intent. */
enum class PlaybackState {
    IDLE,
    SELECTING,
    PREPARING,
    PLAYING,
    REBUFFERING,
    RECOVERING,
    WAITING_FOR_NETWORK,
    RECOVERABLE_ERROR,
    SUSPENDED,
}

enum class PlaybackIntent {
    PLAY,
    PAUSE,
}

enum class PlaybackFailure {
    NETWORK,
    TIMEOUT,
    BEHIND_LIVE_WINDOW,
    UNAUTHORIZED,
    NOT_FOUND,
    MALFORMED_MEDIA,
    DECODER,
    UNKNOWN,
}

/**
 * Monotonic limits for a playback intent. Values are intentionally explicit so
 * tests and device-specific tuning can use the same policy without touching UI.
 */
data class RecoveryBudget(
    val startupDeadlineMs: Long = 20_000L,
    val startupMaxAttempts: Int = 3,
    val recoveryDeadlineMs: Long = 12_000L,
    val recoveryMaxSwitches: Int = 2,
    val recoveryWindowMs: Long = 5 * 60_000L,
    val sameLineRetries: Int = 2,
    val attemptTimeoutMs: Long = 8_000L,
)

data class PlaybackSession(
    val sessionId: Long,
    val channelId: String,
    val networkGeneration: Long,
    val attemptId: Long,
    val selectedLineId: String?,
    val attemptedLineIds: Set<String>,
    val startedAtElapsedMs: Long,
    val recoveryStartedAtElapsedMs: Long?,
    val recoveryWindowStartedAtElapsedMs: Long?,
    val recoverySwitches: Int,
    val sameLineRetries: Int,
    val hasFirstFrame: Boolean,
    val intent: PlaybackIntent,
    val state: PlaybackState,
)

sealed interface PlaybackAction {
    data class Prepare(
        val sessionId: Long,
        val attemptId: Long,
        val lineId: String,
    ) : PlaybackAction

    data class RetrySameLine(
        val sessionId: Long,
        val attemptId: Long,
        val lineId: String,
    ) : PlaybackAction

    data class SwitchLine(
        val sessionId: Long,
        val attemptId: Long,
        val lineId: String,
    ) : PlaybackAction

    data class WaitForNetwork(val sessionId: Long) : PlaybackAction

    data class ShowError(
        val sessionId: Long,
        val channelId: String,
        val failure: PlaybackFailure,
    ) : PlaybackAction

    data object None : PlaybackAction
}
