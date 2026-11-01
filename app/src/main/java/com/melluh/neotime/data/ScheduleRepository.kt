package com.melluh.neotime.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.melluh.neotime.auth.SessionManager
import com.melluh.neotime.timeedit.Area
import com.melluh.neotime.timeedit.DataSource
import com.melluh.neotime.timeedit.Event
import com.melluh.neotime.timeedit.NotLoggedInException
import com.melluh.neotime.timeedit.ObjectRef
import com.melluh.neotime.timeedit.ObjectType
import com.melluh.neotime.timeedit.PublicScheduleMerger
import com.melluh.neotime.timeedit.TIMEEDIT_ZONE
import com.melluh.neotime.timeedit.TimeEditClient
import com.melluh.neotime.timeedit.ViewDiscovery
import com.melluh.neotime.timeedit.Views
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@Serializable
data class ScheduleSnapshot(
    val events: List<Event> = emptyList(),
    val source: DataSource = DataSource.PUBLIC,
    val fetchedAtEpochMs: Long = 0,
    /** Selection the snapshot was fetched for, to detect stale caches after edits. */
    val objects: List<String> = emptyList(),
) {
    val fetchedAt: Instant? get() = fetchedAtEpochMs.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
}

sealed interface RefreshResult {
    data object Ok : RefreshResult
    data class Failed(val message: String) : RefreshResult
}

class ScheduleRepository(
    private val client: TimeEditClient,
    private val session: SessionManager,
    private val dataStore: DataStore<Preferences>,
    private val cacheFile: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val discovery = ViewDiscovery(client)

    private val _snapshot = MutableStateFlow(readCache())
    val snapshot: StateFlow<ScheduleSnapshot> = _snapshot.asStateFlow()

    val selection: Flow<List<ObjectRef>> = dataStore.data.map { prefs ->
        prefs[SELECTION]?.let { runCatching { json.decodeFromString(ListSerializer(ObjectRef.serializer()), it) }.getOrNull() }
            .orEmpty()
    }

    suspend fun add(ref: ObjectRef) = addAll(listOf(ref))

    /** One write, so the schedule refreshes once rather than per item. */
    suspend fun addAll(refs: List<ObjectRef>) = editSelection { (it + refs).distinctBy { r -> r.id } }

    suspend fun remove(ref: ObjectRef) = editSelection { it.filterNot { r -> r.id == ref.id } }

    private suspend fun editSelection(change: (List<ObjectRef>) -> List<ObjectRef>) {
        dataStore.edit { prefs ->
            val current = prefs[SELECTION]?.let { json.decodeFromString(ListSerializer(ObjectRef.serializer()), it) }.orEmpty()
            prefs[SELECTION] = json.encodeToString(ListSerializer(ObjectRef.serializer()), change(current))
        }
    }

    /** Fetches the full logged-in schedule when possible, otherwise the merged public views. */
    suspend fun refresh(): RefreshResult {
        val objects = selection.first().map { it.id }
        if (objects.isEmpty()) {
            save(ScheduleSnapshot(source = _snapshot.value.source, fetchedAtEpochMs = System.currentTimeMillis()))
            return RefreshResult.Ok
        }
        return try {
            val today = LocalDate.now(TIMEEDIT_ZONE)
            val from = today.minusWeeks(4)
            val snapshot = if (session.ensureSession()) {
                try {
                    fetchFull(objects, today, from)
                } catch (e: NotLoggedInException) {
                    fetchPublic(objects, today, from) // session died mid-refresh
                }
            } else {
                fetchPublic(objects, today, from)
            }
            save(snapshot)
            RefreshResult.Ok
        } catch (e: IOException) {
            RefreshResult.Failed("Offline. Showing the last downloaded schedule.")
        } catch (e: Exception) {
            RefreshResult.Failed(e.message ?: "Refresh failed")
        }
    }

    private suspend fun fetchFull(objects: List<String>, today: LocalDate, from: LocalDate): ScheduleSnapshot {
        val view = views(Area.STUDENT).year(Area.STUDENT, today) ?: throw IOException("No logged-in schedule view")
        val events = client.schedule(Area.STUDENT, view.sid, maxOf(from, view.start), view.end, objects)
        return ScheduleSnapshot(events.sortedBy { it.start }, DataSource.FULL, System.currentTimeMillis(), objects)
    }

    private suspend fun fetchPublic(objects: List<String>, today: LocalDate, from: LocalDate): ScheduleSnapshot {
        val views = views(Area.PUBLIC)
        val year = views.year(Area.PUBLIC, today)
        val upcoming = views.upcoming(Area.PUBLIC)
        val yearEvents = year?.let { client.schedule(Area.PUBLIC, it.sid, maxOf(from, it.start), it.end, objects) }.orEmpty()
        val upcomingEvents = upcoming?.let { client.schedule(Area.PUBLIC, it.sid, from, today.plusWeeks(6), objects) }.orEmpty()
        val events = PublicScheduleMerger.merge(yearEvents, upcomingEvents)
        return ScheduleSnapshot(events, DataSource.PUBLIC, System.currentTimeMillis(), objects)
    }

    /** Search in the richer logged-in index when possible. */
    suspend fun search(type: ObjectType, text: String): List<ObjectRef> {
        val today = LocalDate.now(TIMEEDIT_ZONE)
        if (session.state.value !is com.melluh.neotime.auth.SessionState.LoggedOut) {
            val view = views(Area.STUDENT).year(Area.STUDENT, today)
            if (view != null) {
                try {
                    return client.search(Area.STUDENT, view.sid, type, text)
                } catch (e: NotLoggedInException) {
                    // fall through to public search
                }
            }
        }
        val sid = views(Area.PUBLIC).upcoming(Area.PUBLIC)?.sid ?: return emptyList()
        return client.search(Area.PUBLIC, sid, type, text)
    }

    /** View ids change yearly, so they're discovered and cached for a week. */
    suspend fun views(area: Area): Views {
        val prefs = dataStore.data.first()
        val key = if (area == Area.PUBLIC) VIEWS_PUBLIC else VIEWS_STUDENT
        val fetchedKey = if (area == Area.PUBLIC) VIEWS_PUBLIC_AT else VIEWS_STUDENT_AT
        val cached = prefs[key]?.let { runCatching { json.decodeFromString(Views.serializer(), it) }.getOrNull() }
        val fresh = prefs[fetchedKey]?.let { Duration.ofMillis(System.currentTimeMillis() - it) < VIEWS_MAX_AGE } == true
        if (cached != null && fresh) return cached
        return try {
            val discovered = Views(discovery.discover(area))
            if (discovered.views.none { it.isYear }) throw IOException("No views found")
            dataStore.edit {
                it[key] = json.encodeToString(Views.serializer(), discovered)
                it[fetchedKey] = System.currentTimeMillis()
            }
            discovered
        } catch (e: NotLoggedInException) {
            cached ?: Views.FALLBACK
        } catch (e: IOException) {
            cached ?: Views.FALLBACK
        }
    }

    private suspend fun save(snapshot: ScheduleSnapshot) {
        _snapshot.value = snapshot
        withContext(Dispatchers.IO) {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(ScheduleSnapshot.serializer(), snapshot))
            tmp.renameTo(cacheFile)
        }
    }

    private fun readCache(): ScheduleSnapshot =
        runCatching { json.decodeFromString(ScheduleSnapshot.serializer(), cacheFile.readText()) }.getOrNull()
            ?: ScheduleSnapshot()

    private companion object {
        val SELECTION = stringPreferencesKey("selection")
        val VIEWS_PUBLIC = stringPreferencesKey("views_public")
        val VIEWS_PUBLIC_AT = longPreferencesKey("views_public_at")
        val VIEWS_STUDENT = stringPreferencesKey("views_student")
        val VIEWS_STUDENT_AT = longPreferencesKey("views_student_at")
        val VIEWS_MAX_AGE: Duration = Duration.ofDays(7)
    }
}
