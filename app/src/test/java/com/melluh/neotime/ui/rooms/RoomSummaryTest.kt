package com.melluh.neotime.ui.rooms

import com.melluh.neotime.data.RoomStatus
import com.melluh.neotime.timeedit.Availability
import com.melluh.neotime.timeedit.Room
import com.melluh.neotime.timeedit.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RoomSummaryTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val room = Room("1", "CR 2020")

    private fun range(from: String, to: String) = TimeRange(LocalTime.parse(from), LocalTime.parse(to))

    /** What the room looks like at [now] with [busy] reserved, the way the repository builds it. */
    private fun summary(now: String, vararg busy: TimeRange): RoomSummary {
        val periods = busy.toList()
        val at = LocalTime.parse(now)
        val free = Availability.freeRanges(periods, day, LocalDateTime.of(day, at))
        return roomSummary(RoomStatus(room, free, Availability.blocked(periods)), at)
    }

    @Test
    fun `free for the rest of the day`() {
        assertEquals(RoomSummary("Free now", true), summary("14:00"))
    }

    @Test
    fun `free until the next reservation`() {
        assertEquals(RoomSummary("Free until 17:00", true), summary("14:00", range("17:00", "18:00")))
    }

    @Test
    fun `counts as free between slots`() {
        assertEquals(RoomSummary("Free now", true), summary("14:07"))
        assertEquals(RoomSummary("Free now", true), summary("14:07", range("13:00", "14:05")))
    }

    @Test
    fun `an earlier reservation doesn't matter`() {
        assertTrue(summary("14:00", range("09:00", "10:00")).freeNow)
    }

    @Test
    fun `free later for the rest of the day`() {
        assertEquals(RoomSummary("Free from 14:15", false), summary("14:00", range("13:00", "14:15")))
        assertEquals(RoomSummary("Free from 18:00", false), summary("14:00", range("13:00", "18:00")))
    }

    @Test
    fun `free later until another reservation`() {
        assertEquals(
            RoomSummary("Free from 14:15 - 17:00", false),
            summary("14:00", range("13:00", "14:15"), range("17:00", "18:00")),
        )
        assertEquals(
            RoomSummary("Free from 15:00 - 17:00", false),
            summary("14:00", range("13:00", "15:00"), range("17:00", "18:00")),
        )
    }

    @Test
    fun `the gap before a reservation that's too short to book isn't free`() {
        assertEquals(RoomSummary("Free from 15:00", false), summary("14:07", range("14:15", "15:00")))
    }

    @Test
    fun `a gap of 15 minutes is skipped`() {
        assertEquals(RoomSummary("Free from 17:00", false), summary("14:00", range("14:15", "17:00")))
        assertEquals(
            RoomSummary("Free from 17:00", false),
            summary("14:00", range("13:00", "14:30"), range("14:45", "17:00")),
        )
    }

    @Test
    fun `a gap of more than 15 minutes counts`() {
        assertEquals(RoomSummary("Free until 14:20", true), summary("14:00", range("14:20", "17:00")))
    }

    @Test
    fun `only 15 minutes left is fully booked`() {
        assertEquals(RoomSummary("Fully booked today", false), summary("14:00", range("08:00", "14:15"), range("14:30", "23:00")))
    }

    @Test
    fun `not free before opening`() {
        assertEquals(RoomSummary("Free from 08:00", false), summary("07:50"))
        assertEquals(RoomSummary("Free from 08:00", false), summary("06:00"))
    }

    @Test
    fun `fully booked`() {
        assertEquals(RoomSummary("Fully booked today", false), summary("14:00", range("08:00", "23:00")))
    }

    @Test
    fun `nothing left to book after hours isn't called fully booked`() {
        assertEquals(RoomSummary("Closed for today", false), summary("23:10"))
    }
}
