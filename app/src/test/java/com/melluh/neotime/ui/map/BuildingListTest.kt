package com.melluh.neotime.ui.map

import com.melluh.neotime.timeedit.MapBuilding
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildingListTest {
    private val carre = MapBuilding(1, "Carré", emptyList())
    private val horst = MapBuilding(2, "Horst Complex", emptyList())
    private val zilverling = MapBuilding(3, "Zilverling", emptyList())
    private val buildings = listOf(carre, horst, zilverling)

    @Test
    fun `recent buildings come first, latest on top, and aren't repeated`() {
        val list = buildingList(buildings, listOf(3, 2), "")
        assertEquals(listOf(zilverling, horst), list.recent)
        assertEquals(listOf(carre), list.others)
    }

    @Test
    fun `a recent building that's gone from the map is skipped`() {
        assertEquals(listOf(horst), buildingList(buildings, listOf(99, 2), "").recent)
    }

    @Test
    fun `search ignores case, accents and surrounding spaces`() {
        assertEquals(listOf(carre), buildingList(buildings, emptyList(), " CARRE ").others)
    }

    @Test
    fun `search matches anywhere in the name`() {
        assertEquals(listOf(horst), buildingList(buildings, emptyList(), "complex").others)
    }

    @Test
    fun `search narrows recent buildings too`() {
        val list = buildingList(buildings, listOf(3, 2), "horst")
        assertEquals(listOf(horst), list.recent)
        assertEquals(emptyList<MapBuilding>(), list.others)
    }
}
