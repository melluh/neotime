package com.melluh.neotime.timeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
