package com.horsenma.yourtv.models

/** Display numbers are not storage IDs. Reserve explicit numbers before filling gaps. */
object ChannelNavigation {
    fun numbers(requested: List<Int>): List<Int> {
        val reserved = requested.filter { it in 1..9999 }.toSet()
        val used = mutableSetOf<Int>()
        var next = 1
        return requested.map { number ->
            if (number in reserved && used.add(number)) number else {
                while (next in reserved || next in used) next++
                next.also { used.add(it); next++ }
            }
        }
    }

    fun adjacentLine(current: Int, count: Int, direction: Int): Int? {
        if (count < 2) return null
        val step = if (direction < 0) -1 else 1
        return (current.coerceIn(0, count - 1) + step + count) % count
    }
}
