package com.melluh.neotime.timeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleParserTest {

    @Test
    fun `logged-in view has rooms, building, lecturers and location`() {
        val events = ScheduleParser.parse(Fixtures.read("student/ri_sid16_en.json")).events
        assertEquals(44, events.size)
        val first = events.first { it.id == "361702" }
        assertEquals(LocalDateTime.of(2026, 11, 9, 8, 45), first.start)
        assertEquals("M2 TCS / M6D AT Software Systems", first.courseName)
        assertEquals("Lecture", first.activityType)
        assertEquals(listOf("WA 2"), first.rooms)
        assertEquals("Waaier", first.building)
        assertEquals(listOf("F.J. Castor de Lima Filho", "P. van den Bos", "V. Zaytsev"), first.lecturers)
        assertTrue(first.locationUrl!!.startsWith("https://use.mazemap.com/"))
        assertTrue(events.all { it.rooms.isNotEmpty() && it.lecturers.isNotEmpty() })
    }

    @Test
    fun `Dutch headers and dd-mm-yyyy dates parse the same`() {
        val en = ScheduleParser.parse(Fixtures.read("student/ri_sid16_en.json")).events.associateBy { it.id }
        val nl = ScheduleParser.parse(Fixtures.read("student/ri_sid16_nl.json")).events
        assertEquals(en.size, nl.size)
        for (e in nl) {
            val match = en.getValue(e.id)
            assertEquals(match.start, e.start)
            assertEquals(match.rooms, e.rooms)
            assertEquals(match.lecturers, e.lecturers)
        }
    }

    @Test
    fun `public upcoming view has rooms but no lecturers`() {
        val events = ScheduleParser.parse(Fixtures.read("public/ri_sid3.json")).events
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.rooms.isNotEmpty() && it.building != null })
        assertTrue(events.all { it.lecturers.isEmpty() })
    }

    @Test
    fun `public year view has neither rooms nor lecturers`() {
        val events = ScheduleParser.parse(Fixtures.read("public/ri_sid10.json")).events
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.rooms.isEmpty() && it.lecturers.isEmpty() && it.building == null })
    }

    @Test
    fun `full year is not truncated`() {
        val parsed = ScheduleParser.parse(Fixtures.read("student/ri_sid16_year.json"))
        assertFalse(parsed.truncated)
        assertEquals(228, parsed.events.size)
    }

    @Test
    fun `date formats`() {
        val expected = LocalDate.of(2026, 11, 9)
        assertEquals(expected, ScheduleParser.parseDate("09/11/2026"))
        assertEquals(expected, ScheduleParser.parseDate("09-11-2026"))
        assertEquals(expected, ScheduleParser.parseDate("2026-11-09"))
    }

    @Test
    fun `course name strips codes, also for combined courses`() {
        fun name(course: String) = Event("1", LocalDateTime.MIN, LocalDateTime.MIN, course).courseName
        assertEquals("Introduction to Programming", name("Introduction to Programming. 202500342"))
        assertEquals("Calculus A, Calculus B", name("Calculus A. 202000001, Calculus B. 202000002"))
        assertEquals("No code here", name("No code here"))
    }

    @Test
    fun `course codes are found, also for combined courses`() {
        fun codes(course: String) = Event("1", LocalDateTime.MIN, LocalDateTime.MIN, course).courseCodes
        assertEquals(setOf("202500342"), codes("Introduction to Programming. 202500342"))
        assertEquals(setOf("202000001", "202000002"), codes("Calculus A. 202000001, Calculus B. 202000002"))
        assertTrue(codes("No code here").isEmpty())
    }

    @Test
    fun `the academic year starts on the monday of the week of 1 September`() {
        fun year(date: LocalDate) = Event("1", date.atStartOfDay(), date.atStartOfDay(), "").academicYear
        assertEquals(2025, year(LocalDate.of(2026, 8, 30)))
        assertEquals(2026, year(LocalDate.of(2026, 8, 31)))
        assertEquals(2026, year(LocalDate.of(2026, 12, 17)))
        assertEquals(2026, year(LocalDate.of(2027, 8, 29)))
        assertEquals(2027, year(LocalDate.of(2027, 8, 30)))
    }

    @Test
    fun `missing columns become null`() {
        val body = """{"columnheaders":["Course"],"info":{"reservationlimit":1000,"reservationcount":1},
            "reservations":[{"id":"1","startdate":"01/02/2027","starttime":"09:00","enddate":"01/02/2027","endtime":"10:00","columns":["X. 123456"]}]}"""
        val e = ScheduleParser.parse(body).events.single()
        assertNull(e.building)
        assertTrue(e.rooms.isEmpty())
    }
}
