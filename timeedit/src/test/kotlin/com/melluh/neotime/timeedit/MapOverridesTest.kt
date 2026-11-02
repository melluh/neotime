package com.melluh.neotime.timeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOverridesTest {
    private val building = MapBuilding(1, "Ravelijn", emptyList())
    private val room = MapFeature(5, "room", "Collegezaal NH117", "HCNH117", listOf("Hallway"))
    private val door = MapEntrance(floorId = 9, at = GeoPoint(6.85, 52.24))

    @Test
    fun `a building gets its bearing`() {
        val overrides = MapOverrides.parse("""{"buildings": {"1": {"name": "for humans", "bearing": 30}}}""")
        assertEquals(30.0, overrides.applyTo(listOf(building)).single().bearing, 0.0)
    }

    @Test
    fun `a building without overrides stays as it is`() {
        val overrides = MapOverrides.parse("""{"buildings": {"2": {"bearing": 30}}}""")
        assertEquals(listOf(building), overrides.applyTo(listOf(building)))
    }

    @Test
    fun `an override that sets nothing changes nothing`() {
        val overrides = MapOverrides.parse("""{"buildings": {"1": {}}, "pois": {"5": {}}}""")
        assertEquals(listOf(building), overrides.applyTo(listOf(building)))
        assertEquals(listOf(room), overrides.applyToFeatures(listOf(room)))
    }

    @Test
    fun `an empty file is fine`() {
        assertEquals(listOf(building), MapOverrides.parse("{}").applyTo(listOf(building)))
    }

    @Test
    fun `a building gets its entrances and keeps its bearing`() {
        val overrides = MapOverrides(mapOf(1L to BuildingOverride(bearing = 30.0))).withEntrance(1, door)
        val result = overrides.applyTo(listOf(building)).single()
        assertEquals(listOf(door), result.entrances)
        assertEquals(30.0, result.bearing, 0.0)
    }

    @Test
    fun `an entrance can be added to a building without overrides and taken away again`() {
        val added = MapOverrides().withEntrance(1, door)
        assertEquals(listOf(door), added.applyTo(listOf(building)).single().entrances)
        assertTrue(added.withoutEntrance(1, door).applyTo(listOf(building)).single().entrances.isEmpty())
    }

    @Test
    fun `changing a room's type makes it what it says`() {
        val hall = PoiOverride(kind = "room", typeNames = listOf("Lecture Hall"))
        val result = MapOverrides().withPoi(5, hall).applyToFeatures(listOf(room)).single()
        assertEquals(listOf("Lecture Hall"), result.typeNames)
        assertEquals("Collegezaal NH117", result.title)
    }

    @Test
    fun `a room can be given an empty type list`() {
        val plain = PoiOverride(typeNames = emptyList())
        assertTrue(MapOverrides().withPoi(5, plain).applyToFeatures(listOf(room)).single().typeNames.isEmpty())
    }

    @Test
    fun `a room can go back to what MazeMap says`() {
        val overrides = MapOverrides().withPoi(5, PoiOverride(kind = "stairs")).withPoi(5, null)
        assertEquals(listOf(room), overrides.applyToFeatures(listOf(room)))
    }

    @Test
    fun `an entrance's direction is turned along with the map`() {
        assertEquals(0.0, door.copy(direction = 301.5).directionOnMap(301.5)!!, 1e-9)
        assertEquals(90.0, door.copy(direction = 31.5).directionOnMap(301.5)!!, 1e-9)
        assertEquals(270.0, door.copy(direction = 10.0).directionOnMap(100.0)!!, 1e-9)
        assertEquals(null, door.directionOnMap(301.5))
    }

    @Test
    fun `entrances marked before there were directions still read`() {
        val text = """{"buildings": {"1": {"entrances": [{"floorId": 9, "at": {"lon": 6.85, "lat": 52.24}, "main": true}]}}}"""
        assertEquals(door.copy(main = true), MapOverrides.parse(text).buildings.getValue(1).entrances.single())
    }

    @Test
    fun `everything survives being written out and read back`() {
        val overrides = MapOverrides(mapOf(1L to BuildingOverride(name = "Ravelijn", bearing = 31.5)))
            .withEntrance(1, door.copy(main = true, direction = 211.5))
            .withPoi(5, PoiOverride(note = "HCNH117", kind = "room", typeNames = listOf("Lecture Hall")))
        assertEquals(overrides, MapOverrides.parse(overrides.encode()))
    }
}
