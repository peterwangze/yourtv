package com.horsenma.yourtv

import java.util.concurrent.ConcurrentHashMap

/**
 * 线路健康状态（会话级内存缓存）：
 * 后台渐进探测所有频道的首选线路，切台时跳过已确认不可达的线路。
 */
object LineHealth {
    private val probed = ConcurrentHashMap.newKeySet<String>()
    private val dead = ConcurrentHashMap.newKeySet<String>()
    private val latencyMap = ConcurrentHashMap<String, Long>()

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
    }

    fun reset() {
        probed.clear()
        dead.clear()
        latencyMap.clear()
    }
}
