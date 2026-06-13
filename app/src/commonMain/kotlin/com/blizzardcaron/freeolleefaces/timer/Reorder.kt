package com.blizzardcaron.freeolleefaces.timer

/** Pure list reordering — returns a new list; a no-op when the move is out of bounds. */
object Reorder {

    /** Swap the element at [index] with the one before it. No-op if [index] <= 0 or out of range. */
    fun <T> moveUp(list: List<T>, index: Int): List<T> = swap(list, index, index - 1)

    /** Swap the element at [index] with the one after it. No-op if [index] is last or out of range. */
    fun <T> moveDown(list: List<T>, index: Int): List<T> = swap(list, index, index + 1)

    private fun <T> swap(list: List<T>, a: Int, b: Int): List<T> {
        if (a !in list.indices || b !in list.indices) return list
        val out = list.toMutableList()
        out[a] = list[b]
        out[b] = list[a]
        return out
    }
}
