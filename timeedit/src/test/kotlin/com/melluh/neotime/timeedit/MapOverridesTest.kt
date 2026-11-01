package com.melluh.neotime.timeedit

import org.junit.Assert.assertEquals
import org.junit.Test

class MapOverridesTest {
    private val building = MapBuilding(1, "Ravelijn", emptyList())

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
        val overrides = MapOverrides.parse("""{"buildings": {"1": {}}}""")
        assertEquals(listOf(building), overrides.applyTo(listOf(building)))
    }

    @Test
    fun `an empty file is fine`() {
        assertEquals(listOf(building), MapOverrides.parse("{}").applyTo(listOf(building)))
    }
}
