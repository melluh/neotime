package com.melluh.neotime.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.melluh.neotime.timeedit.MapBuilding
import com.melluh.neotime.timeedit.MapFeature
import com.melluh.neotime.timeedit.MapOverrides
import com.melluh.neotime.timeedit.MazeMapClient
import com.melluh.neotime.timeedit.MazeMapException
import com.melluh.neotime.timeedit.RoomLocation
import com.melluh.neotime.timeedit.RoomNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Duration

sealed interface MapResult<out T> {
    /** [stale] means the cached copy is past its age and couldn't be renewed. */
    data class Ok<T>(val value: T, val stale: Boolean = false) : MapResult<T>
    data class Failed(val message: String) : MapResult<Nothing>
}

/** Both null until something's been picked. */
data class MapSelection(val buildingId: Long?, val floorId: Long?)

@Serializable
private data class Cached<T>(val fetchedAtEpochMs: Long, val value: T)

/** A lookup's outcome; [location] is null when MazeMap doesn't know the identifier. */
@Serializable
private data class CheckedLocation(val checkedAtEpochMs: Long, val location: RoomLocation? = null)

/**
 * MazeMap's data barely changes, so it's fetched on demand and kept for a week. [readOverrides]
 * gives the [MapOverrides] JSON, which goes on top of the cached copy, not into it: a new app
 * version's overrides then count at once, rather than whenever the cache runs out.
 */
class MapRepository(
    private val client: MazeMapClient,
    private val dataStore: DataStore<Preferences>,
    private val cacheDir: File,
    private val readOverrides: () -> String = { "{}" },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val overrides by lazy { MapOverrides.parse(readOverrides()) }
    private val locationsFile = File(cacheDir, "room-locations.json")
    private val locationsSerializer = MapSerializer(String.serializer(), CheckedLocation.serializer())
    private val locationsLock = Mutex()
    private var locations: Map<String, CheckedLocation>? = null

    val lastSelection: Flow<MapSelection> = dataStore.data.map { MapSelection(it[LAST_BUILDING], it[LAST_FLOOR]) }

    /** Building ids, latest first. */
    val recentBuildings: Flow<List<Long>> = dataStore.data.map(::recentBuildings)

    suspend fun select(buildingId: Long, floorId: Long?) {
        dataStore.edit {
            val recent = (listOf(buildingId) + recentBuildings(it)).distinct().take(RECENT_LIMIT)
            it[RECENT_BUILDINGS] = recent.joinToString(",")
            it[LAST_BUILDING] = buildingId
            if (floorId != null) it[LAST_FLOOR] = floorId else it.remove(LAST_FLOOR)
        }
    }

    // Installs from before this list was kept still know their last building
    private fun recentBuildings(prefs: Preferences): List<Long> =
        prefs[RECENT_BUILDINGS]?.split(',')?.mapNotNull { it.toLongOrNull() } ?: listOfNotNull(prefs[LAST_BUILDING])

    suspend fun buildings(): MapResult<List<MapBuilding>> {
        val result = cached("buildings.json", ListSerializer(MapBuilding.serializer())) { client.buildings() }
        if (result !is MapResult.Ok) return result
        return result.copy(value = withContext(Dispatchers.IO) { overrides }.applyTo(result.value))
    }

    suspend fun floorFeatures(floorId: Long): MapResult<List<MapFeature>> =
        cached("floor-$floorId.json", ListSerializer(MapFeature.serializer())) { client.floorFeatures(floorId) }

    /**
     * Where the room called [name] is, asking MazeMap only about rooms that are actually opened.
     * Prefer [identifier] if TimeEdit gave one; otherwise this tries what the name could translate to.
     * [MapResult.Ok] holds null when the room isn't on the map.
     */
    suspend fun locate(name: String, identifier: String? = null): MapResult<RoomLocation?> = locationsLock.withLock {
        if (locations == null) locations = readLocations()
        var failure: String? = null
        for (candidate in identifier?.let(::listOf) ?: RoomNames.identifierCandidates(name)) {
            val entry = locations?.get(candidate)
            if (entry != null && isCurrent(entry)) {
                if (entry.location != null) return@withLock MapResult.Ok(entry.location)
                continue
            }
            try {
                val location = client.locate(candidate)
                saveLocation(candidate, CheckedLocation(now(), location))
                if (location != null) return@withLock MapResult.Ok(location)
            } catch (e: IOException) {
                entry?.location?.let { return@withLock MapResult.Ok(it, stale = true) }
                failure = OFFLINE_ROOM
            } catch (e: MazeMapException) {
                entry?.location?.let { return@withLock MapResult.Ok(it, stale = true) }
                failure = e.message ?: "Couldn't find this room"
            }
        }
        failure?.let { MapResult.Failed(it) } ?: MapResult.Ok(null)
    }

    private suspend fun readLocations(): Map<String, CheckedLocation> = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(locationsSerializer, locationsFile.readText()) }.getOrNull().orEmpty()
    }

    private suspend fun saveLocation(identifier: String, entry: CheckedLocation) {
        val updated = locations.orEmpty() + (identifier to entry)
        locations = updated
        write(locationsFile, locationsSerializer, updated)
    }

    private fun isCurrent(entry: CheckedLocation): Boolean {
        val age = Duration.ofMillis(now() - entry.checkedAtEpochMs)
        return age < if (entry.location != null) LOCATION_MAX_AGE else MISSING_MAX_AGE
    }

    private suspend fun <T> cached(name: String, serializer: KSerializer<T>, fetch: suspend () -> T): MapResult<T> {
        val file = File(cacheDir, name)
        val entry = Cached.serializer(serializer)
        val cached = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(entry, file.readText()) }.getOrNull()
        }
        if (cached != null && isFresh(cached.fetchedAtEpochMs)) return MapResult.Ok(cached.value)
        return try {
            val value = fetch()
            write(file, entry, Cached(now(), value))
            MapResult.Ok(value)
        } catch (e: IOException) {
            cached?.let { MapResult.Ok(it.value, stale = true) } ?: MapResult.Failed(OFFLINE)
        } catch (e: MazeMapException) {
            cached?.let { MapResult.Ok(it.value, stale = true) } ?: MapResult.Failed(e.message ?: "Couldn't load the map")
        }
    }

    private suspend fun <T> write(file: File, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val tmp = File(cacheDir, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        tmp.renameTo(file)
    }

    private fun isFresh(fetchedAt: Long) = Duration.ofMillis(now() - fetchedAt) < MAX_AGE

    private companion object {
        const val OFFLINE = "Offline. This map hasn't been downloaded yet."
        const val OFFLINE_ROOM = "Offline. This room hasn't been looked up yet."

        // Ids from MazeMap; RoomRepository's room_building is a TimeEdit building name
        val LAST_BUILDING = longPreferencesKey("map_building_id")
        val LAST_FLOOR = longPreferencesKey("map_floor_id")
        val RECENT_BUILDINGS = stringPreferencesKey("map_recent_building_ids")
        const val RECENT_LIMIT = 3
        val MAX_AGE: Duration = Duration.ofDays(7)
        val LOCATION_MAX_AGE: Duration = Duration.ofDays(30)
        val MISSING_MAX_AGE: Duration = Duration.ofDays(7)
    }
}
