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
    }

    @Test
    fun `what isn't a room in a known building has no identifier`() {
        assertNull(RoomNames.officeIdentifier("Carré"))
        assertNull(RoomNames.officeIdentifier("Spiegel kantoortuin"))
        assertNull(RoomNames.officeIdentifier("Capitool 15 022"))
    }
}
