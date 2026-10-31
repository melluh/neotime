package com.melluh.neotime.timeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicScheduleMergerTest {
    @Test
    fun `rooms from the upcoming view are merged into the year view by id`() {
        val year = ScheduleParser.parse(Fixtures.read("public/ri_sid10.json")).events
        val upcoming = ScheduleParser.parse(Fixtures.read("public/ri_sid3.json")).events
        val merged = PublicScheduleMerger.merge(year, upcoming)

        val ids = (year.map { it.id } + upcoming.map { it.id }).toSet()
        assertEquals(ids.size, merged.size)
        val upcomingIds = upcoming.map { it.id }.toSet()
        assertTrue(merged.filter { it.id in upcomingIds }.all { it.rooms.isNotEmpty() })
        assertTrue(merged.filter { it.id !in upcomingIds }.all { it.rooms.isEmpty() })
        assertEquals(merged.sortedBy { it.start }, merged)
    }
}
