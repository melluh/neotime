package com.melluh.neotime.timeedit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomNamesTest {
    private val places = ScheduleParser.parse(Fixtures.read("student/ri_sid16_year.json")).events.flatMap { it.places }

    private fun roomNames(building: String) = Json.parseToJsonElement(Fixtures.read("student/rooms/rooms_$building.json"))
        .jsonObject.getValue("objects").jsonArray
        .map { it.jsonObject.getValue("fields").jsonObject.getValue("Description").jsonPrimitive.content }

    @Test
    fun `names are cleaned of what varies between sources`() {
        assertEquals("VR 170", RoomNames.canonical("VR_170"))
        assertEquals("VR 275C", RoomNames.canonical("VR 275C (7p, Library, Inclusion & Well Being)"))
        assertEquals("CR 3014", RoomNames.canonical("  CR  3014 (6p)"))
    }

    /** The real check: everything TimeEdit links to translates to that same identifier. */
    @Test
    fun `names translate to the identifiers TimeEdit links to`() {
        val linked = places.filter { it.identifier != null }.distinct()
        assertEquals(36, linked.size)
        for (place in linked) {
            assertTrue(place.name, place.identifier in RoomNames.identifierCandidates(place.name))
        }
    }

    @Test
    fun `building codes are translated`() {
        assertEquals(listOf("HCT1001", "HT1001"), RoomNames.identifierCandidates("HT 1001"))
        assertEquals(listOf("CRC3014", "CR3014"), RoomNames.identifierCandidates("CR 3014 TNW students only!"))
        assertEquals(listOf("VR170"), RoomNames.identifierCandidates("VR_170"))
    }

    @Test
    fun `details on a name don't get in the way of an override`() {
        assertEquals(listOf("SPC101"), RoomNames.identifierCandidates("SP 1 (200p)"))
    }

    @Test
    fun `every captured project room has a candidate`() {
        for (name in listOf("carre", "horsttoren", "vrijhof").flatMap(::roomNames)) {
            assertTrue(name, RoomNames.identifierCandidates(name).isNotEmpty())
        }
    }

    @Test
    fun `what isn't a room has no candidates`() {
        for (name in listOf("**No Room Required", "*Request Exam Room Allocation", "", "Unknown hall")) {
            assertEquals(name, emptyList<String>(), RoomNames.identifierCandidates(name))
        }
    }

    @Test
    fun `rooms are paired with their links only when all of them have one`() {
        val url = "https://use.mazemap.com/#v=1&campusid=171&sharepoitype=identifier&sharepoi="
        fun event(rooms: List<String>, location: String?) = Event(
            "1", Fixed.START, Fixed.START.plusHours(1), "", rooms = rooms, locationUrl = location,
        )

        assertEquals(
            listOf(RoomPlace("SP 1", "SPC101"), RoomPlace("SP 2", "SPC105")),
            event(listOf("SP 1", "SP 2"), "${url}SPC101, ${url}SPC105").places,
        )
        assertEquals(
            listOf(RoomPlace("SP 1", null), RoomPlace("SP 2", null)),
            event(listOf("SP 1", "SP 2"), "${url}SPC101").places,
        )
        assertEquals(listOf(RoomPlace("SP 1", null)), event(listOf("SP 1"), null).places)
    }

    private object Fixed {
        val START: java.time.LocalDateTime = java.time.LocalDateTime.of(2026, 11, 3, 9, 0)
    }
}
