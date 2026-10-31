package com.melluh.neotime.timeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ViewDiscoveryTest {
    private fun view(area: Area, file: String) =
        ViewDiscovery.parseViewPage(area, "label", Fixtures.read(file))!!

    @Test
    fun `landing links are found with labels`() {
        val public = ViewDiscovery.landingLinks(Fixtures.read("public/landing.html"))
        assertEquals(
            listOf("ri1Q7.html" to "Schedule", "ri1Q50.html" to "Academic Year 2026/2027", "ri1Q2.html" to "Academic Year 2025/2026"),
            public,
        )
        val student = ViewDiscovery.landingLinks(Fixtures.read("student/landing.html")).map { it.first }
        assertEquals(listOf("ri1Q51.html", "ri1Q57.html", "ri1Q8.html", "ri1Q56.html"), student)
    }

    @Test
    fun `view pages give sid, range and types`() {
        val year = view(Area.PUBLIC, "public/view_2026.html")
        assertEquals(10, year.sid)
        assertEquals(LocalDate.of(2026, 8, 31), year.start)
        assertEquals(LocalDate.of(2027, 8, 29), year.end)
        assertTrue(year.isYear)

        val upcoming = view(Area.PUBLIC, "public/view_upcoming.html")
        assertEquals(3, upcoming.sid)
        assertFalse(upcoming.isYear)

        val rooms = view(Area.STUDENT, "student/view_rooms.html")
        assertEquals(12, rooms.sid)
        assertTrue(rooms.isRoomViewer)

        assertNull(ViewDiscovery.parseViewPage(Area.STUDENT, "booking", Fixtures.read("student/view_booking.html")))
    }

    @Test
    fun `views are selected by date`() {
        val views = Views(
            listOf(
                view(Area.PUBLIC, "public/view_upcoming.html"),
                view(Area.PUBLIC, "public/view_2025.html"),
                view(Area.PUBLIC, "public/view_2026.html"),
                view(Area.STUDENT, "student/view_2025.html"),
                view(Area.STUDENT, "student/view_2026.html"),
                view(Area.STUDENT, "student/view_rooms.html"),
            ),
        )
        assertEquals(10, views.year(Area.PUBLIC, LocalDate.of(2026, 11, 10))?.sid)
        assertEquals(7, views.year(Area.PUBLIC, LocalDate.of(2026, 3, 1))?.sid)
        assertEquals(16, views.year(Area.STUDENT, LocalDate.of(2026, 11, 10))?.sid)
        assertEquals(13, views.year(Area.STUDENT, LocalDate.of(2026, 3, 1))?.sid)
        assertEquals(3, views.upcoming(Area.PUBLIC)?.sid)
        assertEquals(12, views.rooms(Area.STUDENT)?.sid)
    }

    @Test
    fun `fallback covers the current year`() {
        assertEquals(16, Views.FALLBACK.year(Area.STUDENT, LocalDate.of(2026, 11, 10))?.sid)
        assertEquals(10, Views.FALLBACK.year(Area.PUBLIC, LocalDate.of(2026, 11, 10))?.sid)
        assertEquals(3, Views.FALLBACK.upcoming(Area.PUBLIC)?.sid)
    }
}
