package com.melluh.neotime.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.melluh.neotime.auth.SessionManager
import com.melluh.neotime.auth.SessionState
import com.melluh.neotime.timeedit.Area
import com.melluh.neotime.timeedit.Availability
import com.melluh.neotime.timeedit.BookingView
import com.melluh.neotime.timeedit.NotLoggedInException
import com.melluh.neotime.timeedit.Room
import com.melluh.neotime.timeedit.TIMEEDIT_ZONE
import com.melluh.neotime.timeedit.TimeEditClient
import com.melluh.neotime.timeedit.TimeEditException
import com.melluh.neotime.timeedit.TimeRange
import com.melluh.neotime.timeedit.ViewDiscovery
import com.melluh.neotime.timeedit.Views
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

data class RoomStatus(val room: Room, val free: List<TimeRange>, val freeNow: Boolean)

sealed interface RoomsResult {
    data class Ok(val rooms: List<RoomStatus>) : RoomsResult
    data object NeedsLogin : RoomsResult
    data class Failed(val message: String) : RoomsResult
}

@Serializable
private data class CachedRooms(val fetchedAtEpochMs: Long, val rooms: List<Room>)

@Serializable
private data class CachedBooking(val fetchedAtEpochMs: Long, val view: BookingView)

class RoomRepository(
    private val client: TimeEditClient,
    private val session: SessionManager,
    private val schedule: ScheduleRepository,
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val discovery = ViewDiscovery(client)
    private val roomCache = MapSerializer(String.serializer(), CachedRooms.serializer())

    val favorites: Flow<List<Room>> = dataStore.data.map { readFavorites(it) }

    val lastBuilding: Flow<String?> = dataStore.data.map { it[LAST_BUILDING] }

    suspend fun toggleFavorite(room: Room) {
        dataStore.edit { prefs ->
            val current = readFavorites(prefs)
            val updated = if (current.any { it.id == room.id }) current.filterNot { it.id == room.id } else current + room
            prefs[FAVORITES] = json.encodeToString(ListSerializer(Room.serializer()), updated)
        }
    }

    suspend fun selectBuilding(building: String) {
        dataStore.edit { it[LAST_BUILDING] = building }
    }

    private fun readFavorites(prefs: Preferences): List<Room> =
        prefs[FAVORITES]?.let { runCatching { json.decodeFromString(ListSerializer(Room.serializer()), it) }.getOrNull() }.orEmpty()

    /** Buildings and booking window, discovered weekly; never fails, so the tab can always render. */
    suspend fun bookingView(): BookingView {
        val cached = dataStore.data.first()[BOOKING]
            ?.let { runCatching { json.decodeFromString(CachedBooking.serializer(), it) }.getOrNull() }
        if (cached != null && isFresh(cached.fetchedAtEpochMs)) return cached.view
        return try {
            val view = discovery.discoverBooking() ?: throw IOException("No booking page")
            dataStore.edit {
                it[BOOKING] = json.encodeToString(CachedBooking.serializer(), CachedBooking(System.currentTimeMillis(), view))
            }
            view
        } catch (e: NotLoggedInException) {
            cached?.view ?: BookingView.FALLBACK
        } catch (e: IOException) {
            cached?.view ?: BookingView.FALLBACK
        }
    }

    /** Free time on [date] for the rooms in [building], plus all favorites wherever they are. */
    suspend fun availability(building: String, date: LocalDate): RoomsResult {
        // Goes through the session manager so any renewal shows in the banner
        if (!session.ensureSession()) {
            return when (session.state.value) {
                is SessionState.LoggedIn, is SessionState.Renewing -> RoomsResult.Failed(OFFLINE)
                else -> RoomsResult.NeedsLogin
            }
        }
        return try {
            val rooms = (rooms(building) + favorites.first()).distinctBy { it.id }
            val sid = (schedule.views(Area.STUDENT).rooms(Area.STUDENT) ?: Views.FALLBACK.rooms(Area.STUDENT))!!.sid
            // The booking view's sid has no Hall column, so occupancy comes from the room viewer
            val events = client.schedule(Area.STUDENT, sid, date, date, rooms.map { it.id })
            val busy = Availability.busyByRoom(events, rooms, date)
            val now = LocalDateTime.now(TIMEEDIT_ZONE)
            val slot = now.toLocalTime().let(Availability::nextSlot)
            RoomsResult.Ok(
                rooms.map { room ->
                    val free = Availability.freeRanges(busy[room.id].orEmpty(), date, now)
                    RoomStatus(room, free, freeNow = date == now.toLocalDate() && free.firstOrNull()?.start == slot)
                },
            )
        } catch (e: NotLoggedInException) {
            RoomsResult.NeedsLogin
        } catch (e: IOException) {
            RoomsResult.Failed(OFFLINE)
        } catch (e: TimeEditException) {
            RoomsResult.Failed(e.message ?: "Couldn't load rooms")
        }
    }

    private suspend fun rooms(building: String): List<Room> {
        val cache = dataStore.data.first()[ROOMS]
            ?.let { runCatching { json.decodeFromString(roomCache, it) }.getOrNull() }.orEmpty()
        val cached = cache[building]
        if (cached != null && isFresh(cached.fetchedAtEpochMs)) return cached.rooms
        val sid = bookingView().sid
        val rooms = try {
            client.rooms(sid, building)
        } catch (e: IOException) {
            return cached?.rooms ?: throw e
        }
        dataStore.edit { prefs ->
            val current = prefs[ROOMS]?.let { runCatching { json.decodeFromString(roomCache, it) }.getOrNull() }.orEmpty()
            prefs[ROOMS] = json.encodeToString(roomCache, current + (building to CachedRooms(System.currentTimeMillis(), rooms)))
        }
        return rooms
    }

    private fun isFresh(fetchedAt: Long) = Duration.ofMillis(System.currentTimeMillis() - fetchedAt) < MAX_AGE

    private companion object {
        const val OFFLINE = "Offline. Availability needs a connection."
        val FAVORITES = stringPreferencesKey("room_favorites")
        val LAST_BUILDING = stringPreferencesKey("room_building")
        val BOOKING = stringPreferencesKey("room_booking_view")
        val ROOMS = stringPreferencesKey("room_lists")
        val MAX_AGE: Duration = Duration.ofDays(7)
    }
}
