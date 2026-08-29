package com.turbolego.songguesser

/**
 * FIFO queue used by the game to keep upcoming videos ready.
 * Entries are unique by their YouTube ID while they are queued.
 */
internal class SongQueue<T>(private val idOf: (T) -> String) {
    private val entries = ArrayDeque<T>()

    val size: Int
        get() = entries.size

    fun containsId(id: String): Boolean = entries.any { idOf(it) == id }

    fun add(entry: T): Boolean {
        if (containsId(idOf(entry))) return false
        entries.addLast(entry)
        return true
    }

    fun poll(): T? = if (entries.isEmpty()) null else entries.removeFirst()

    fun removeById(id: String): Boolean {
        val entry = entries.firstOrNull { idOf(it) == id } ?: return false
        entries.remove(entry)
        return true
    }

    fun clear() = entries.clear()
}
