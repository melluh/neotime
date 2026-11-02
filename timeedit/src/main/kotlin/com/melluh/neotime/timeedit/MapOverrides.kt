package com.melluh.neotime.timeedit

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What we know better than MazeMap, kept apart from its data so that both can change on their
 * own. Buildings go by MazeMap id, and [pois] by [MapFeature.id]. One that no longer exists there
 * is simply never looked up.
 */
@Serializable
data class MapOverrides(
    val buildings: Map<Long, BuildingOverride> = emptyMap(),
    val pois: Map<Long, PoiOverride> = emptyMap(),
) {
    fun applyTo(buildings: List<MapBuilding>): List<MapBuilding> = buildings.map { building ->
        this.buildings[building.id]?.let {
            building.copy(bearing = it.bearing ?: building.bearing, entrances = it.entrances)
        } ?: building
    }

    fun applyToFeatures(features: List<MapFeature>): List<MapFeature> = features.map { feature ->
        pois[feature.id]?.let {
            feature.copy(kind = it.kind ?: feature.kind, typeNames = it.typeNames ?: feature.typeNames)
        } ?: feature
    }

    fun withEntrance(buildingId: Long, entrance: MapEntrance): MapOverrides =
        withEntrances(buildingId) { it + entrance }

    fun withoutEntrance(buildingId: Long, entrance: MapEntrance): MapOverrides =
        withEntrances(buildingId) { it - entrance }

    private fun withEntrances(buildingId: Long, change: (List<MapEntrance>) -> List<MapEntrance>): MapOverrides {
        val building = buildings[buildingId] ?: BuildingOverride()
        return copy(buildings = buildings + (buildingId to building.copy(entrances = change(building.entrances))))
    }

    /** Null [override] goes back to what MazeMap says. */
    fun withPoi(poiId: Long, override: PoiOverride?): MapOverrides =
        copy(pois = if (override != null) pois + (poiId to override) else pois - poiId)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
            explicitNulls = false
        }

        fun parse(text: String): MapOverrides = json.decodeFromString(text)
    }
}

/** [name] is only there for whoever reads the file, as is [PoiOverride.note]. [bearing] is as on [MapBuilding]. */
@Serializable
data class BuildingOverride(
    val name: String? = null,
    val bearing: Double? = null,
    val entrances: List<MapEntrance> = emptyList(),
)

/** Null fields leave MazeMap's value alone. See [MapFeature] for [kind] and [typeNames]. */
@Serializable
data class PoiOverride(
    val note: String? = null,
    val kind: String? = null,
    val typeNames: List<String>? = null,
)
