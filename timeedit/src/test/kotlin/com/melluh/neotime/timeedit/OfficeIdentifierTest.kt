package com.melluh.neotime.timeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeIdentifierTest {
    @Test
    fun `offices map to the identifiers MazeMap uses`() {
        assertEquals("ZI3035", RoomNames.officeIdentifier("Zilverling 3035"))
        assertEquals("RA1438", RoomNames.officeIdentifier("Ravelijn 1438"))
        assertEquals("LA2418", RoomNames.officeIdentifier("Langezijds 2418"))
        assertEquals("SP625", RoomNames.officeIdentifier("Spiegel 625"))
        assertEquals("HCME328", RoomNames.officeIdentifier("Horst Complex ME328"))
        assertEquals("CRC4641", RoomNames.officeIdentifier("Carré C4641"))
        assertEquals("CRC3718", RoomNames.officeIdentifier("Carré 3718"))
        assertEquals("BO116", RoomNames.officeIdentifier("Boerderij 116"))
        assertEquals("CIH200", RoomNames.officeIdentifier("Citadel H200"))
        assertEquals("NL1001", RoomNames.officeIdentifier("Nanolab 1001"))
        assertEquals("PA104", RoomNames.officeIdentifier("Paviljoen 104"))
        assertEquals("SC114", RoomNames.officeIdentifier("Sportcentrum 114"))
        assertEquals("TL2101", RoomNames.officeIdentifier("Technohal 2101"))
        assertEquals("VR131", RoomNames.officeIdentifier("Vrijhof 131"))
    }

    @Test
    fun `what isn't a room in a known building has no identifier`() {
        assertNull(RoomNames.officeIdentifier("Carré"))
        assertNull(RoomNames.officeIdentifier("Spiegel kantoortuin"))
        assertNull(RoomNames.officeIdentifier("Capitool 15 022"))
    }

    @Test
    fun `an office is searched for by its room code alone`() {
        assertEquals(listOf("NH107"), RoomNames.officeSearchTerms("HCNH107"))
        assertEquals(listOf("3035"), RoomNames.officeSearchTerms("ZI3035"))
        assertEquals(listOf("H200"), RoomNames.officeSearchTerms("CIH200"))
    }

    @Test
    fun `Carre rooms are searched for in both spellings`() {
        assertEquals(listOf("C3045", "3045"), RoomNames.officeSearchTerms("CRC3045"))
    }

    @Test
    fun `search terms lead back to the same identifier`() {
        listOf("Zilverling 3035", "Horst Complex ME328", "Ravelijn 1438", "Spiegel 625", "Carré C4641", "Carré 3718")
            .forEach { office ->
                val identifier = RoomNames.officeIdentifier(office)!!
                assertTrue(office, office.substringAfterLast(' ') in RoomNames.officeSearchTerms(identifier))
            }
    }

    @Test
    fun `what can't be an office has no search terms`() {
        assertEquals(emptyList<String>(), RoomNames.officeSearchTerms("WA200VE"))
        assertEquals(emptyList<String>(), RoomNames.officeSearchTerms("ZI"))
        assertEquals(emptyList<String>(), RoomNames.officeSearchTerms("CRC"))
        assertEquals(emptyList<String>(), RoomNames.officeSearchTerms("ZI-toilets"))
    }
}
