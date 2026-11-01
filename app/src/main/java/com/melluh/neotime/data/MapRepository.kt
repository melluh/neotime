package com.melluh.neotime.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.melluh.neotime.timeedit.MapBuilding
import com.melluh.neotime.timeedit.MapFeature
import com.melluh.neotime.timeedit.MazeMapClient
import com.melluh.neotime.timeedit.MazeMapException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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

/** MazeMap's data barely changes, so it's fetched on demand and kept for a week. */
class MapRepository(
    private val client: MazeMapClient,
    private val dataStore: DataStore<Preferences>,
    private val cacheDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val lastSelection: Flow<MapSelection> = dataStore.data.map { MapSelection(it[LAST_BUILDING], it[LAST_FLOOR]) }

    suspend fun select(buildingId: Long, floorId: Long?) {
        dataStore.edit {
            it[LAST_BUILDING] = buildingId
            if (floorId != null) it[LAST_FLOOR] = floorId else it.remove(LAST_FLOOR)
        }
    }

    suspend fun buildings(): MapResult<List<MapBuilding>> =
        cached("buildings.json", ListSerializer(MapBuilding.serializer())) { client.buildings() }

    suspend fun floorFeatures(floorId: Long): MapResult<List<MapFeature>> =
        cached("floor-$floorId.json", ListSerializer(MapFeature.serializer())) { client.floorFeatures(floorId) }

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

        // Ids from MazeMap; RoomRepository's room_building is a TimeEdit building name
        val LAST_BUILDING = longPreferencesKey("map_building_id")
        val LAST_FLOOR = longPreferencesKey("map_floor_id")
        val MAX_AGE: Duration = Duration.ofDays(7)
    }
}
