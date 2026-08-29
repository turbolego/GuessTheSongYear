package com.turbolego.songguesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongQueueTest {
    private data class Song(val id: String)

    @Test
    fun `queue keeps unique ids and preserves FIFO order`() {
        val queue = SongQueue<Song> { it.id }
        assertTrue(queue.add(Song("one")))
        assertTrue(queue.add(Song("two")))
        assertFalse(queue.add(Song("one")))

        assertEquals(2, queue.size)
        assertEquals("one", queue.poll()?.id)
        assertEquals("two", queue.poll()?.id)
        assertEquals(null, queue.poll())
    }

    @Test
    fun `failed song can be removed without disturbing replacements`() {
        val queue = SongQueue<Song> { it.id }
        queue.add(Song("one"))
        queue.add(Song("two"))
        queue.add(Song("three"))

        assertTrue(queue.removeById("two"))
        assertFalse(queue.removeById("missing"))
        assertEquals(listOf("one", "three"), listOf(queue.poll()?.id, queue.poll()?.id))
    }
}
