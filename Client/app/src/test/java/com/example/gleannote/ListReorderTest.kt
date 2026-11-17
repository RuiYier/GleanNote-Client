package com.example.gleannote

import com.example.gleannote.core.common.moved
import org.junit.Assert.assertEquals
import org.junit.Test

/* 拖拽排序纯逻辑用例：确认页顺序即笔记页面顺序（PRD FR-03）。 */
class ListReorderTest {

    @Test
    fun moveForwardKeepsOthersOrder() {
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").moved(0, 2))
    }

    @Test
    fun moveBackwardKeepsOthersOrder() {
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, 0))
    }

    @Test
    fun samePositionIsNoop() {
        assertEquals(listOf("a", "b"), listOf("a", "b").moved(1, 1))
    }

    @Test
    fun outOfRangeIsNoop() {
        assertEquals(listOf("a", "b"), listOf("a", "b").moved(-1, 1))
        assertEquals(listOf("a", "b"), listOf("a", "b").moved(0, 5))
    }

    @Test
    fun singleElementIsStable() {
        assertEquals(listOf("only"), listOf("only").moved(0, 0))
    }

    @Test
    fun repeatedMovesAreAssociative() {
        // [1,2,3,4] --move(0→3)--> [2,3,4,1] --move(1→0)--> [3,2,4,1]
        val start = listOf(1, 2, 3, 4)
        val result = start.moved(0, 3).moved(1, 0)
        assertEquals(listOf(3, 2, 4, 1), result)
    }
}
