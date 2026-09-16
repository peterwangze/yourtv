package com.horsenma.yourtv.models

import org.junit.Assert.*
import org.junit.Test

class ChannelNavigationTest {
    @Test fun missingNumbersUseReadableSequence() {
        assertEquals(listOf(1, 2, 3), ChannelNavigation.numbers(listOf(-1, -1, -1)))
    }
    @Test fun explicitNumbersAreReservedBeforeFallbackAllocation() {
        assertEquals(listOf(3, 2, 1, 4), ChannelNavigation.numbers(listOf(-1, 2, 1, -1)))
    }
    @Test fun duplicateOrInvalidNumbersDoNotCreateAmbiguousDialTargets() {
        val numbers = ChannelNavigation.numbers(listOf(5, 5, 0, Int.MAX_VALUE, -182701811))
        assertEquals(listOf(5, 1, 2, 3, 4), numbers)
        assertEquals(numbers.size, numbers.toSet().size)
    }
    @Test fun leftFromFirstLineWrapsToLast() {
        assertEquals(2, ChannelNavigation.adjacentLine(0, 3, -1))
    }
    @Test fun rightFromLastLineWrapsToFirst() {
        assertEquals(0, ChannelNavigation.adjacentLine(2, 3, 1))
    }
    @Test fun oppositeKeysReturnToSameLine() {
        val right = ChannelNavigation.adjacentLine(1, 4, 1)!!
        assertEquals(1, ChannelNavigation.adjacentLine(right, 4, -1))
    }
    @Test fun noAlternativeDoesNotCreateAnAttempt() {
        assertNull(ChannelNavigation.adjacentLine(0, 0, 1))
        assertNull(ChannelNavigation.adjacentLine(0, 1, -1))
    }
}
