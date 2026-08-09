package com.horsenma.yourtv

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 线路健康状态（会话级内存缓存）：
 * 后台渐进探测所有频道的首选线路，切台时跳过已确认不可达的线路。
 * v3.3.0：结果持久化（24h TTL），跨会话记住死线/延迟，避免每次启动
 * 重新踩 40-50% 的腐烂线路（2026-08-09 全量探测：3763 条仅 43-60% 存活）。
 */
object LineHealth {
    private val probed = ConcurrentHashMap.newKeySet<String>()
    private val dead = ConcurrentHashMap.newKeySet<String>()
    private val latencyMap = ConcurrentHashMap<String, Long>()
    private const val TAG = "LineHealth"
    private const val TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 3000
    private val lastPersist = AtomicLong(0L)

    /** 从持久化存储加载（SP.init 后调用一次；仅恢复 24h 内数据） */
    fun loadPersisted() {
        val now = System.currentTimeMillis()
        SP.getLineHealth().forEach { (url, value) ->
            val parts = value.split("|")
            if (parts.size < 2) return@forEach
            val ts = parts[1].toLongOrNull() ?: return@forEach
            if (now - ts > TTL_MS) return@forEach
            val latency = parts[0].toLongOrNull() ?: -1L
            probed.add(url)
            if (latency >= 0) latencyMap[url] = latency
            if (parts.size >= 3 && parts[2] == "d") dead.add(url)
        }
        Log.d(TAG, "loadPersisted: restored ${probed.size} probed, ${dead.size} dead")
    }

    /** 持久化（节流 10s 一次；进程被杀最多丢最近 10s 的探测结果） */
    fun persist() {
        val now = System.currentTimeMillis()
        if (now - lastPersist.get() < 10_000L) return
        lastPersist.set(now)
        try {
            val snapshot = ConcurrentHashMap<String, String>()
            dead.forEach { url ->
                if (snapshot.size < MAX_ENTRIES) snapshot[url] = "-1|$now|d"
            }
            latencyMap.forEach { (url, latency) ->
                if (snapshot.size < MAX_ENTRIES && !snapshot.containsKey(url)) {
                    snapshot[url] = "$latency|$now"
                }
            }
            SP.setLineHealth(snapshot)
        } catch (e: Exception) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }

    fun isProbed(url: String): Boolean = url in probed

    fun isDead(url: String): Boolean = url in dead

    /** 探测延迟（毫秒），未探测返回 null */
    fun latency(url: String): Long? = latencyMap[url]

    fun mark(url: String, ok: Boolean, latencyMs: Long = -1) {
        if (url.isBlank()) return
        probed.add(url)
        if (latencyMs >= 0) {
            latencyMap[url] = latencyMs
        }
        if (!ok) {
            dead.add(url)
        }
        // 探测/播放失败都会落盘（节流在 persist 内部）
        persist()
    }

    fun reset() {
        probed.clear()
        dead.clear()
        latencyMap.clear()
    }
}
