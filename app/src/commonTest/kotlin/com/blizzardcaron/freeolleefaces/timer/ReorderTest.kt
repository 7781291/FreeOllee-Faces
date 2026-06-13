package com.blizzardcaron.freeolleefaces.timer

import kotlin.test.Test
import kotlin.test.assertEquals

class ReorderTest {

    @Test fun moveUp_interior_swapsWithPrevious() {
        assertEquals(listOf("a", "c", "b", "d"), Reorder.moveUp(listOf("a", "b", "c", "d"), 2))
    }

    @Test fun moveUp_atTop_isNoOp() {
        assertEquals(listOf("a", "b", "c"), Reorder.moveUp(listOf("a", "b", "c"), 0))
    }

    @Test fun moveUp_negativeIndex_isNoOp() {
        assertEquals(listOf("a", "b"), Reorder.moveUp(listOf("a", "b"), -1))
    }

    @Test fun moveDown_interior_swapsWithNext() {
        assertEquals(listOf("a", "c", "b", "d"), Reorder.moveDown(listOf("a", "b", "c", "d"), 1))
    }

    @Test fun moveDown_atBottom_isNoOp() {
        assertEquals(listOf("a", "b", "c"), Reorder.moveDown(listOf("a", "b", "c"), 2))
    }

    @Test fun moveDown_indexBeyondEnd_isNoOp() {
        assertEquals(listOf("a", "b"), Reorder.moveDown(listOf("a", "b"), 5))
    }

    @Test fun move_onSingleElement_isNoOp() {
        assertEquals(listOf("x"), Reorder.moveUp(listOf("x"), 0))
        assertEquals(listOf("x"), Reorder.moveDown(listOf("x"), 0))
    }

    @Test fun move_onEmptyList_isNoOp() {
        assertEquals(emptyList(), Reorder.moveUp(emptyList<String>(), 0))
        assertEquals(emptyList(), Reorder.moveDown(emptyList<String>(), 0))
    }

    @Test fun moveUp_doesNotMutateInput() {
        val input = listOf("a", "b", "c")
        val snapshot = input.toList()
        Reorder.moveUp(input, 1)
        assertEquals(snapshot, input)
    }
}
