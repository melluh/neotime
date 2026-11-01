package com.melluh.neotime.ui.rooms

import com.melluh.neotime.timeedit.Room
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RoomFilterTest {
    private val day = LocalDate.of(2026, 11, 3)

    private fun room(capacity: Int?, type: String? = "Project") = Room("1", "CR 2020", capacity, "Carre", type)

    @Test
    fun `any size accepts every room`() {
        val filter = RoomFilter(date = day)
        assertTrue(filter.accepts(room(1)))
        assertTrue(filter.accepts(room(8)))
    }

    @Test
    fun `a size accepts only rooms of that size`() {
        val filter = RoomFilter(date = day, size = 6)
        assertTrue(filter.accepts(room(6)))
        assertFalse(filter.accepts(room(8)))
    }

    @Test
    fun `a room of unknown size is never filtered out by size`() {
        assertTrue(RoomFilter(date = day, size = 6).accepts(room(null)))
    }

    @Test
    fun `size and type both have to match`() {
        val filter = RoomFilter(date = day, size = 6, type = "Project Open")
        assertTrue(filter.accepts(room(6, "Project Open")))
        assertFalse(filter.accepts(room(6, "Project")))
    }
}
