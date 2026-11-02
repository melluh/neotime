package com.melluh.neotime.timeedit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class GeoPoint(val lon: Double, val lat: Double)

/** Rings of a shape, drawn with even-odd fill so that holes and separate parts both come out right. */
typealias Rings = List<List<GeoPoint>>

/** [floors] run from lowest to highest. [bearing] is the compass direction, in degrees, drawn pointing up. */
@Serializable
data class MapBuilding(
    val id: Long,
    val name: String,
    val floors: List<MapFloor>,
    val bearing: Double = 0.0,
    val entrances: List<MapEntrance> = emptyList(),
) {
    /** The ground floor where one is marked, since basements share the lowest [MapFloor.z]. */
    val defaultFloor: MapFloor?
        get() = floors.firstOrNull { it.z >= 1 } ?: floors.firstOrNull()
}

/**
 * A way into a building, from outside. MazeMap has no such thing, so these all come from
 * [MapOverrides]. [direction] is the compass direction you walk in, in degrees. It's not relative
 * to the map, so that it stays right if a building's bearing changes.
 */
@Serializable
data class MapEntrance(val floorId: Long, val at: GeoPoint, val main: Boolean = false, val direction: Double? = null) {
    /** Clockwise from straight up on a map drawn with [bearing] pointing up. */
    fun directionOnMap(bearing: Double): Double? = direction?.let { (it - bearing).mod(360.0) }
}

/** [name] is what the building calls it; order by [z], because floor "0" can have z = -1. */
@Serializable
data class MapFloor(val id: Long, val name: String, val z: Double, val outline: Rings? = null)

/**
 * Something on a floor. [identifier] is the id TimeEdit's MazeMap links use, such as `RA4237`, and
 * isn't unique; [id] is. [rings] is null for point-only features such as a printer.
 */
@Serializable
data class MapFeature(
    val id: Long,
    val kind: String,
    val title: String? = null,
    val identifier: String? = null,
    val typeNames: List<String> = emptyList(),
    val rings: Rings? = null,
    val labelPoint: GeoPoint? = null,
) {
    /**
     * Whether this is somewhere you'd go on purpose, like a lecture hall, office or project room.
     * Most of those have no type in MazeMap, so this rules out the rest instead of picking them.
     */
    val isDestination: Boolean
        get() = title != null && kind !in PASSING_THROUGH_KINDS &&
            typeNames.none { type -> type in BACKGROUND_TYPES || type.startsWith("Toilet") } &&
            !CORRIDOR_CODE.matches(title)

    /** What to call this on the map, if anything. */
    val label: String?
        get() = title?.takeIf { isDestination }?.removePrefix(LECTURE_HALL_PREFIX)
}

/** Where a room is, enough to open its floor and point it out. */
@Serializable
data class RoomLocation(val poiId: Long, val identifier: String, val buildingId: Long, val floorId: Long)

/** Nearly every lecture hall has this in front of the name people actually use. */
private const val LECTURE_HALL_PREFIX = "Collegezaal "

private val PASSING_THROUGH_KINDS = setOf("circulation_room", "stairs", "elevator", "desk", "generic", "zone")

private val BACKGROUND_TYPES = setOf(
    "Hallway", "Traffic Area", "Technical Room", "Technische ruimte", "Elevators", "Stairs", "Lockers",
)

/** Corridors and entrances are numbered like `100VC` or `C000VE`, typed or not. */
private val CORRIDOR_CODE = Regex("[A-Z]*\\d+V[A-Z]{1,2}")

class MazeMapException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Client for MazeMap's public JSON API. Give it an [http] client without login cookies: nothing
 * here needs them, and they're no business of a third party.
 */
class MazeMapClient(
    private val http: OkHttpClient,
    private val base: HttpUrl = DEFAULT_BASE,
    private val campusId: Int = UT_CAMPUS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** All buildings on the campus by name, with their floors. */
    suspend fun buildings(): List<MapBuilding> {
        val url = base.newBuilder()
            .addPathSegments("api/buildings/")
            .addQueryParameter("campusid", campusId.toString())
            .addQueryParameter("srid", SRID)
            .build()
        return get(url).let { withContext(Dispatchers.Default) { parseBuildings(it) } }
    }

    /** Everything drawn on one floor. */
    suspend fun floorFeatures(floorId: Long): List<MapFeature> {
        val url = base.newBuilder()
            .addPathSegments("api/pois/")
            .addQueryParameter("floorid", floorId.toString())
            .addQueryParameter("srid", SRID)
            .build()
        return get(url).let { withContext(Dispatchers.Default) { parseFeatures(it) } }
    }

    /** Where the room with this [identifier] is; null if nowhere, or if that can't be told for sure. */
    suspend fun locate(identifier: String): RoomLocation? {
        val url = base.newBuilder()
            .addPathSegments("api/pois/")
            .addQueryParameter("campusid", campusId.toString())
            .addQueryParameter("identifier", identifier)
            .addQueryParameter("srid", SRID)
            .build()
        return get(url).let { withContext(Dispatchers.Default) { parseLocation(identifier, it) } }
    }

    // Whole floors are a lot of JSON, and these get called from the main thread
    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw MazeMapException("HTTP ${r.code} for ${url.encodedPath}")
            r.body?.string().orEmpty()
        }
    }

    internal fun parseBuildings(body: String): List<MapBuilding> = parsing {
        json.decodeFromString<RawBuildings>(body).buildings.map { b ->
            MapBuilding(
                id = b.id,
                name = b.name,
                floors = b.floors.map { MapFloor(it.id, it.name, it.z, it.outline?.rings()) }.sortedBy { it.z },
            )
        }.sortedBy { it.name }
    }

    internal fun parseFeatures(body: String): List<MapFeature> = parsing {
        val raw = json.decodeFromString<RawPois>(body)
        // Pages end at the limit, and half a floor looks like a real map
        if (raw.limit > 0 && raw.pois.size >= raw.limit) throw MazeMapException("Too many features on one floor")
        raw.pois.filterNot { it.deleted }.map { p ->
            MapFeature(
                id = p.poiId,
                kind = p.kind.orEmpty(),
                title = p.title?.trim()?.ifEmpty { null },
                identifier = p.identifier,
                typeNames = p.types.mapNotNull { it.name },
                rings = p.geometry?.rings(),
                labelPoint = p.point?.point() ?: p.geometry?.point(),
            )
        }
    }

    internal fun parseLocation(identifier: String, body: String): RoomLocation? = parsing {
        val found = json.decodeFromString<RawPois>(body).pois.filter { !it.deleted && it.identifier == identifier }
        val pois = found.filter { it.kind == "room" }.ifEmpty { found }
        val floors = pois.mapNotNull { p -> p.buildingId?.let { b -> p.floorId?.let { f -> b to f } } }.distinct()
        // Identifiers aren't unique, and pointing at the wrong room is worse than at none
        floors.singleOrNull()?.let { (building, floor) -> RoomLocation(pois.first().poiId, identifier, building, floor) }
    }

    private inline fun <T> parsing(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw MazeMapException("Unexpected MazeMap response", e)
    } catch (e: IllegalArgumentException) { // a coordinate that isn't one
        throw MazeMapException("Unexpected MazeMap response", e)
    } catch (e: IndexOutOfBoundsException) {
        throw MazeMapException("Unexpected MazeMap response", e)
    }

    companion object {
        val DEFAULT_BASE = "https://api.mazemap.com".toHttpUrl()
        const val UT_CAMPUS = 171
        const val USER_AGENT = "neotime"
        private const val SRID = "4326"
    }
}

@Serializable
private data class RawBuildings(val buildings: List<RawBuilding> = emptyList())

@Serializable
private data class RawBuilding(val id: Long, val name: String, val floors: List<RawFloor> = emptyList())

@Serializable
private data class RawFloor(val id: Long, val name: String, val z: Double, val outline: RawGeometry? = null)

@Serializable
private data class RawPois(val pois: List<RawPoi> = emptyList(), val limit: Int = 0)

@Serializable
private data class RawPoi(
    val poiId: Long,
    val kind: String? = null,
    val title: String? = null,
    val identifier: String? = null,
    val buildingId: Long? = null,
    val floorId: Long? = null,
    val deleted: Boolean = false,
    val types: List<RawPoiType> = emptyList(),
    val geometry: RawGeometry? = null,
    val point: RawGeometry? = null,
)

@Serializable
private data class RawPoiType(val name: String? = null)

/** GeoJSON, whose [coordinates] nest differently per [type]. */
@Serializable
private data class RawGeometry(val type: String, val coordinates: JsonElement = JsonArray(emptyList())) {
    fun rings(): Rings? = when (type) {
        "Polygon" -> coordinates.polygon()
        "MultiPolygon" -> coordinates.jsonArray.flatMap { it.polygon() }
        else -> null
    }

    fun point(): GeoPoint? = if (type == "Point") coordinates.geoPoint() else null

    private fun JsonElement.polygon(): Rings = jsonArray.map { ring -> ring.jsonArray.map { it.geoPoint() } }

    private fun JsonElement.geoPoint(): GeoPoint = jsonArray.let { GeoPoint(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
}
