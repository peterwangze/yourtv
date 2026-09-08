package com.horsenma.yourtv

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/**
 * Per-line health learned on this device and network.
 *
 * A playback success is stronger evidence than a list-level probe. Failures
 * use a bounded cooldown instead of a permanent dead flag, so an ISP route or
 * signed URL can recover without waiting for an app-data reset.
 */
object LineHealth {
    private data class Record(
        var latencyMs: Long = -1L,
        var lastCheckMs: Long = 0L,
        var lastSuccessMs: Long = 0L,
        var lastPlaybackSuccessMs: Long = 0L,
        var lastFailureMs: Long = 0L,
        var failureStreak: Int = 0,
        var retryAfterMs: Long = 0L,
    )

    private val records = ConcurrentHashMap<String, Record>()
    private val lastPersist = AtomicLong(0L)
    private val persistPending = AtomicBoolean(false)
    private val persistDirty = AtomicBoolean(false)
    private val persistGeneration = AtomicLong(0L)
    private val persistExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "LineHealthPersist").apply { isDaemon = true }
    }

    fun loadPersisted() {
        val now = System.currentTimeMillis()
        persistGeneration.incrementAndGet()
        records.clear()
        SP.getLineHealth().forEach { (url, value) ->
            parse(value)?.takeIf { now - it.lastCheckMs <= RECORD_TTL_MS }?.let { records[url] = it }
        }
        Log.d(TAG, "loadPersisted: restored ${records.size} records, cooling=${records.keys.count(::isDead)}")
    }

    /** Probe-result compatibility API. Playback events use the stronger methods below. */
    fun mark(url: String, ok: Boolean, latencyMs: Long = -1L) {
        if (ok) markProbeSuccess(url, latencyMs) else markProbeFailure(url, latencyMs)
    }

    fun markPlaybackSuccess(url: String, latencyMs: Long = -1L) {
        markSuccess(url, latencyMs, playback = true)
    }

    fun markProbeSuccess(url: String, latencyMs: Long = -1L) {
        markSuccess(url, latencyMs, playback = false)
    }

    private fun markSuccess(url: String, latencyMs: Long, playback: Boolean) {
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        val record = records.computeIfAbsent(url) { Record() }
        synchronized(record) {
            record.latencyMs = mergeLatency(record.latencyMs, latencyMs)
            record.lastCheckMs = now
            record.lastSuccessMs = now
            if (playback) record.lastPlaybackSuccessMs = now
            record.failureStreak = 0
            record.retryAfterMs = 0L
        }
        persist()
    }

    fun markProbeFailure(url: String, latencyMs: Long = -1L) {
        markFailure(url, latencyMs, playback = false)
    }

    fun markPlaybackFailure(url: String) {
        markFailure(url, -1L, playback = true)
    }

    private fun markFailure(url: String, latencyMs: Long, playback: Boolean) {
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        val record = records.computeIfAbsent(url) { Record() }
        synchronized(record) {
            record.latencyMs = mergeLatency(record.latencyMs, latencyMs)
            record.lastCheckMs = now
            record.lastFailureMs = now
            record.failureStreak = (record.failureStreak + 1).coerceAtMost(8)
            val cooldown = when {
                playback -> maxOf(PLAYBACK_FAILURE_COOLDOWN_MS, probeCooldown(record.failureStreak))
                else -> probeCooldown(record.failureStreak)
            }
            record.retryAfterMs = now + cooldown
        }
        persist()
    }

    fun isProbed(url: String): Boolean {
        val record = records[url] ?: return false
        return System.currentTimeMillis() - record.lastCheckMs <= PROBE_FRESH_MS
    }

    fun shouldProbe(url: String): Boolean {
        val record = records[url] ?: return true
        val now = System.currentTimeMillis()
        if (record.retryAfterMs > now) return false
        if (record.lastFailureMs > record.lastSuccessMs) return true
        return now - record.lastCheckMs > HEALTHY_REPROBE_MS
    }

    /** Compatibility name used by the player: true means temporarily cooling down. */
    fun isDead(url: String): Boolean = records[url]?.retryAfterMs?.let { it > System.currentTimeMillis() } == true

    /** 4=played here, 3=older playback, 2=probe success, 1=unknown, 0=cooling down. */
    fun healthRank(url: String): Int {
        val record = records[url] ?: return 1
        val now = System.currentTimeMillis()
        if (record.retryAfterMs > now) return 0
        if (record.lastPlaybackSuccessMs >= record.lastFailureMs && record.lastPlaybackSuccessMs > 0L) {
            return if (now - record.lastPlaybackSuccessMs <= FRESH_SUCCESS_MS) 4 else 3
        }
        if (record.lastSuccessMs >= record.lastFailureMs && record.lastSuccessMs > 0L) {
            return 2
        }
        return 1
    }

    fun latency(url: String): Long? = records[url]?.latencyMs?.takeIf { it >= 0L }

    fun reset() {
        persistGeneration.incrementAndGet()
        records.clear()
        persistDirty.set(false)
        SP.setLineHealth(emptyMap())
    }

    fun persist(force: Boolean = false) {
        val now = System.currentTimeMillis()
        persistDirty.set(true)
        if (!persistPending.compareAndSet(false, true)) return

        val generation = persistGeneration.get()
        val delayMs = if (force) 0L else {
            (PERSIST_THROTTLE_MS - (now - lastPersist.get())).coerceAtLeast(0L)
        }
        persistExecutor.schedule({
            val snapshotTime = System.currentTimeMillis()
            persistDirty.set(false)
            try {
                if (generation != persistGeneration.get()) return@schedule
                val snapshot = records.entries
                    .asSequence()
                    .mapNotNull { (url, record) ->
                        val copy = synchronized(record) { record.copy() }
                        if (snapshotTime - copy.lastCheckMs <= RECORD_TTL_MS) url to copy else null
                    }
                    .sortedByDescending { it.second.lastCheckMs }
                    .take(MAX_ENTRIES)
                    .associate { (url, record) ->
                        val value = listOf(
                            FORMAT_VERSION,
                            record.latencyMs,
                            record.lastCheckMs,
                            record.lastSuccessMs,
                            record.lastPlaybackSuccessMs,
                            record.lastFailureMs,
                            record.failureStreak,
                            record.retryAfterMs,
                        ).joinToString("|")
                        url to value
                    }
                if (generation == persistGeneration.get()) {
                    SP.setLineHealth(snapshot)
                    lastPersist.set(snapshotTime)
                }
            } catch (e: Exception) {
                Log.w(TAG, "persist failed: ${e.message}")
            } finally {
                persistPending.set(false)
                if (persistDirty.get()) persist()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun parse(value: String): Record? {
        val parts = value.split('|')
        if (parts.firstOrNull() == FORMAT_VERSION && parts.size >= 8) {
            return Record(
                latencyMs = parts[1].toLongOrNull() ?: -1L,
                lastCheckMs = parts[2].toLongOrNull() ?: return null,
                lastSuccessMs = parts[3].toLongOrNull() ?: 0L,
                lastPlaybackSuccessMs = parts[4].toLongOrNull() ?: 0L,
                lastFailureMs = parts[5].toLongOrNull() ?: 0L,
                failureStreak = parts[6].toIntOrNull() ?: 0,
                retryAfterMs = parts[7].toLongOrNull() ?: 0L,
            )
        }

        // v3.4 development format: 2|latency|check|success|failure|streak|retryAfter
        if (parts.firstOrNull() == "2" && parts.size >= 7) {
            return Record(
                latencyMs = parts[1].toLongOrNull() ?: -1L,
                lastCheckMs = parts[2].toLongOrNull() ?: return null,
                lastSuccessMs = parts[3].toLongOrNull() ?: 0L,
                lastFailureMs = parts[4].toLongOrNull() ?: 0L,
                failureStreak = parts[5].toIntOrNull() ?: 0,
                retryAfterMs = parts[6].toLongOrNull() ?: 0L,
            )
        }

        // v3.3 format: latency|timestamp[|d]
        if (parts.size >= 2) {
            val latency = parts[0].toLongOrNull() ?: -1L
            val timestamp = parts[1].toLongOrNull() ?: return null
            val failed = parts.getOrNull(2) == "d"
            return Record(
                latencyMs = latency,
                lastCheckMs = timestamp,
                lastSuccessMs = if (failed) 0L else timestamp,
                lastFailureMs = if (failed) timestamp else 0L,
                failureStreak = if (failed) 1 else 0,
                retryAfterMs = if (failed) timestamp + LEGACY_FAILURE_COOLDOWN_MS else 0L,
            )
        }
        return null
    }

    private fun mergeLatency(old: Long, measured: Long): Long {
        if (measured < 0L) return old
        if (old < 0L) return measured
        return (old * 0.7 + measured * 0.3).roundToLong()
    }

    private fun probeCooldown(streak: Int): Long = when (streak) {
        0, 1 -> 60_000L
        2 -> 5 * 60_000L
        3 -> 30 * 60_000L
        else -> 2 * 60 * 60_000L
    }

    private const val TAG = "LineHealth"
    private const val FORMAT_VERSION = "3"
    private const val MAX_ENTRIES = 3_000
    private const val PERSIST_THROTTLE_MS = 10_000L
    private const val PROBE_FRESH_MS = 30 * 60_000L
    private const val HEALTHY_REPROBE_MS = 6 * 60 * 60_000L
    private const val FRESH_SUCCESS_MS = 24 * 60 * 60_000L
    private const val RECORD_TTL_MS = 7 * 24 * 60 * 60_000L
    private const val PLAYBACK_FAILURE_COOLDOWN_MS = 10 * 60_000L
    private const val LEGACY_FAILURE_COOLDOWN_MS = 5 * 60_000L
}
