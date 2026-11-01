package com.melluh.neotime.timeedit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What we know better than MazeMap, kept apart from its data so that both can change on their
 * own. Buildings go by MazeMap id. One that no longer exists there is simply never looked up.
 */
@Serializable
data class MapOverrides(val buildings: Map<Long, BuildingOverride> = emptyMap()) {
    fun applyTo(buildings: List<MapBuilding>): List<MapBuilding> = buildings.map { building ->
        this.buildings[building.id]?.let { building.copy(bearing = it.bearing ?: building.bearing) } ?: building
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): MapOverrides = json.decodeFromString(text)
    }
}

/** [bearing] is as on [MapBuilding]. Null fields leave MazeMap's value alone. */
@Serializable
data class BuildingOverride(val bearing: Double? = null)
