package com.melluh.neotime.timeedit

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RoomsTest {
    private val client = TimeEditClient(OkHttpClient())
    private val tuesday = LocalDate.of(2026, 11, 3)
    private val before = tuesday.minusDays(1).atTime(12, 0)

    private fun t(value: String) = LocalTime.parse(value)
    private fun range(from: String, to: String) = TimeRange(t(from), t(to))

    private fun rooms(building: String) =
        client.parseRooms(Fixtures.read("student/rooms/rooms_${building.lowercase()}.json"), building)

    private fun events(file: String) = ScheduleParser.parse(Fixtures.read("student/rooms/$file")).events

    @Test
    fun `an empty day is free during bookable hours`() {
        assertEquals(listOf(range("08:00", "23:00")), Availability.freeRanges(emptyList(), tuesday, before))
    }

    @Test
    fun `overlapping and adjacent bookings merge`() {
        val busy = listOf(range("13:00", "14:00"), range("09:00", "12:00"), range("10:00", "11:00"), range("12:00", "12:30"))
        assertEquals(
            listOf(range("08:00", "09:00"), range("12:30", "13:00"), range("14:00", "23:00")),
            Availability.freeRanges(busy, tuesday, before),
        )
    }

    @Test
    fun `a fully booked day has no free ranges`() {
        assertEquals(emptyList<TimeRange>(), Availability.freeRanges(listOf(range("07:00", "23:30")), tuesday, before))
    }

    @Test
    fun `bookings outside bookable hours are ignored`() {
        assertEquals(
            listOf(range("08:00", "23:00")),
            Availability.freeRanges(listOf(range("06:00", "07:30"), range("23:15", "23:45")), tuesday, before),
        )
    }

    @Test
    fun `today starts at the next slot`() {
        val now = tuesday.atTime(13, 7, 30)
        assertEquals(
            listOf(range("13:15", "15:00"), range("16:00", "23:00")),
            Availability.freeRanges(listOf(range("09:00", "10:00"), range("15:00", "16:00")), tuesday, now),
        )
    }

    @Test
    fun `a booking running now pushes the first free range back`() {
        assertEquals(
            listOf(range("14:00", "23:00")),
            Availability.freeRanges(listOf(range("13:00", "14:00")), tuesday, tuesday.atTime(13, 30)),
        )
    }

    @Test
    fun `slot rounding handles boundaries`() {
        assertEquals(t("13:15"), Availability.nextSlot(t("13:15")))
        assertEquals(t("14:00"), Availability.nextSlot(t("13:45:01")))
        assertEquals(t("08:00"), Availability.nextSlot(t("06:00")))
        assertEquals(t("23:00"), Availability.nextSlot(t("22:50")))
        assertNull(Availability.nextSlot(t("23:00")))
    }

    @Test
    fun `nothing is free late in the evening or on past days`() {
        assertEquals(emptyList<TimeRange>(), Availability.freeRanges(emptyList(), tuesday, tuesday.atTime(22, 50)))
        assertEquals(emptyList<TimeRange>(), Availability.freeRanges(emptyList(), tuesday, tuesday.atTime(23, 30)))
        assertEquals(emptyList<TimeRange>(), Availability.freeRanges(emptyList(), tuesday, tuesday.plusDays(1).atTime(9, 0)))
    }

    @Test
    fun `a booking lasting until midnight is handled`() {
        val event = Event("1", tuesday.atTime(18, 0), tuesday.atTime(LocalTime.MAX), "", rooms = listOf("RA 2209 (8p)"))
        val room = Room("1.4", "RA 2209")
        val busy = Availability.busyByRoom(listOf(event), listOf(room), tuesday).getValue("1.4")
        assertEquals(listOf(range("08:00", "18:00")), Availability.freeRanges(busy, tuesday, before))
    }

    @Test
    fun `hall names are matched to rooms despite their variations`() {
        val rooms = listOf(
            Room("1.4", "VR 275C"), Room("2.4", "VR 275"), Room("3.4", "CR 3014 TNW students only!"),
            Room("4.4", "VR 176"), Room("5.4", "VR_170"), Room("6.4", "SP PR 1"), Room("7.4", "SP PR 10"),
        )
        fun matched(hall: String) = Availability.busyByRoom(
            listOf(Event("e", tuesday.atTime(9, 0), tuesday.atTime(10, 0), "", rooms = ScheduleParser.splitList(hall))),
            rooms,
            tuesday,
        ).keys
        assertEquals(setOf("1.4"), matched("VR 275C (7p, Library, Inclusion & Well Being)"))
        assertEquals(setOf("3.4"), matched("CR 3014 (6p)"))
        assertEquals(setOf("4.4"), matched("VR 176 1p, (Library)"))
        assertEquals(setOf("5.4"), matched("VR 170 (7p, Library)"))
        assertEquals(setOf("6.4"), matched("SP PR 1 (8p)"))
        assertEquals(setOf("2.4", "1.4"), matched("VR 275 (4p), VR 275C (7p)"))
        assertEquals(emptySet<String>(), matched("Unknown hall"))
    }

    /** The real check: computed availability equals TimeEdit's own free filter, on captured data. */
    @Test
    fun `computed availability matches the free filter`() {
        for ((building, date) in listOf("Vrijhof" to LocalDate.of(2026, 10, 31), "Carre" to tuesday)) {
            val rooms = rooms(building)
            val day = date.toString().replace("-", "")
            val busy = Availability.busyByRoom(events("occupancy_${building.lowercase()}_$day.json"), rooms, date)
            for (time in listOf("1045", "1300", "1800")) {
                val start = t("${time.take(2)}:${time.drop(2)}")
                val free = rooms.filter { room ->
                    Availability.freeRanges(busy[room.id].orEmpty(), date, date.minusDays(1).atStartOfDay())
                        .any { !it.start.isAfter(start) && !it.end.isBefore(start.plusMinutes(30)) }
                }.map { it.id }.sorted()
                val expected = Json.decodeFromString(
                    ListSerializer(String.serializer()),
                    Fixtures.read("student/rooms/free_${building.lowercase()}_${day}_$time.json"),
                )
                assertEquals("$building $day $time", expected.sorted(), free)
            }
        }
    }

    @Test
    fun `a booking across several rooms blocks each of them`() {
        val date = LocalDate.of(2026, 11, 2)
        val rooms = rooms("Horsttoren")
        val busy = Availability.busyByRoom(events("occupancy_horsttoren_20261102.json"), rooms, date)
        assertFalse(busy.isEmpty())
        val first = rooms.first { it.name == "HT 1001" }
        val last = rooms.first { it.name == "HT 1105" }
        assertEquals(true, busy.getValue(first.id).intersect(busy.getValue(last.id).toSet()).isNotEmpty())
    }

    @Test
    fun `rooms parse from English and Dutch fields`() {
        val english = rooms("Carre").first { it.id == "1484.4" || it.name.startsWith("CR 2020") }
        assertEquals("CR 2020", english.name)
        assertEquals("Carre", english.building)
        val dutch = client.parseRooms(
            """{"hasMore":false,"objects":[{"idAndType":"1488.4","fields":{"Beschrijving":"RA 2209","Capaciteit":"8","Gebouw":"Ravelijn","Type":"Project"}}]}""",
            "Ravelijn",
        )
        assertEquals(listOf(Room("1488.4", "RA 2209", 8, "Ravelijn", "Project")), dutch)
    }

    @Test
    fun `rooms building name comes from the filter`() {
        assertEquals(setOf("Horsttoren"), rooms("Horsttoren").map { it.building }.toSet())
    }

    @Test
    fun `truncated room lists are rejected`() {
        assertThrows(TimeEditException::class.java) { client.parseRooms("""{"hasMore":true,"objects":[]}""", "Vrijhof") }
    }

    @Test
    fun `room requests filter by building and project type`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(Fixtures.read("student/rooms/rooms_carre.json")))
            val rooms = TimeEditClient(OkHttpClient(), server.url("/nl_utwente/web")).rooms(4, "Carre")
            assertEquals(14, rooms.size)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/nl_utwente/web/student/objects.json", url.encodedPath)
            assertEquals("4", url.queryParameter("sid"))
            assertEquals(listOf("20.Carre", "74.Project,Project%20Open"), url.queryParameterValues("fe"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `booking page gives sid, buildings and window`() {
        val view = ViewDiscovery.parseBookingPage(Fixtures.read("student/view_booking.html"))!!
        assertEquals(BookingView.FALLBACK, view)
    }

    @Test
    fun `other pages are not booking pages`() {
        assertNull(ViewDiscovery.parseBookingPage(Fixtures.read("student/view_rooms.html")))
    }
}
