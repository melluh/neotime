package com.melluh.neotime.data

import com.melluh.neotime.timeedit.PeopleDirectory
import com.melluh.neotime.timeedit.PersonDetails
import com.melluh.neotime.timeedit.PersonMatch
import com.melluh.neotime.timeedit.isPlaceholderLecturer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** A finished lookup. A null [match] means the name didn't resolve to exactly one person. */
@Serializable
data class LecturerEntry(
    val match: PersonMatch? = null,
    val fetchedAtEpochMs: Long = 0,
    val profile: LecturerProfile? = null,
)

@Serializable
data class LecturerProfile(val details: PersonDetails, val fetchedAtEpochMs: Long)

/** Remembers lookups across launches, since each one downloads a ~130 KB results page. */
class LecturerRepository(
    private val directory: PeopleDirectory,
    private val cacheFile: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), LecturerEntry.serializer())
    private val writeLock = Mutex()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val profilesInFlight = ConcurrentHashMap.newKeySet<String>()

    private val _entries = MutableStateFlow(readCache())

    /** Past lookups by lecturer name; an old entry stays visible while it's refreshed. */
    val entries: StateFlow<Map<String, LecturerEntry>> = _entries.asStateFlow()

    fun searchUrl(name: String): String = directory.searchUrl(name)

    /** Looks up [names] that are missing or expired. Failures stay uncached, to retry next time. */
    suspend fun load(names: List<String>) = coroutineScope {
        names.distinct()
            .filterNot { isPlaceholderLecturer(it) || isFresh(_entries.value[it]) }
            .forEach { launch { fetch(it) } }
    }

    private suspend fun fetch(name: String) {
        if (!inFlight.add(name)) return
        try {
            val match = directory.lookup(name)
            _entries.update { entries ->
                val profile = entries[name]?.takeIf { it.match?.profileUrl == match?.profileUrl }?.profile
                entries + (name to LecturerEntry(match, now(), profile))
            }
            save()
        } catch (e: IOException) {
            // Offline or site trouble: the row falls back to the search link
        } finally {
            inFlight.remove(name)
        }
    }

    /**
     * Reads the profile page of [name], who must have a match, unless that's been done lately.
     * This is left for when someone asks, as most lecturers' cards never get opened.
     * Returns false if it had to be read and couldn't be.
     */
    suspend fun loadProfile(name: String): Boolean {
        val entry = _entries.value[name]
        val url = entry?.match?.profileUrl ?: return true
        if (entry.profile?.let { isRecent(it.fetchedAtEpochMs, MATCH_MAX_AGE) } == true) return true
        if (!profilesInFlight.add(name)) return true
        return try {
            val profile = LecturerProfile(directory.details(url), now())
            _entries.update { entries ->
                val current = entries[name]?.takeIf { it.match?.profileUrl == url } ?: return@update entries
                entries + (name to current.copy(profile = profile))
            }
            save()
            true
        } catch (e: IOException) {
            false
        } finally {
            profilesInFlight.remove(name)
        }
    }

    private fun isFresh(entry: LecturerEntry?): Boolean {
        if (entry == null) return false
        // Cached before names were stored: refresh now rather than show initials for another month
        if (entry.match != null && entry.match.displayName == null) return false
        return isRecent(entry.fetchedAtEpochMs, if (entry.match != null) MATCH_MAX_AGE else MISS_MAX_AGE)
    }

    private fun isRecent(fetchedAtEpochMs: Long, maxAge: Duration): Boolean =
        Duration.ofMillis(now() - fetchedAtEpochMs) < maxAge

    private suspend fun save() = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, _entries.value))
            tmp.renameTo(cacheFile)
        }
    }

    private fun readCache(): Map<String, LecturerEntry> =
        runCatching { json.decodeFromString(serializer, cacheFile.readText()) }.getOrNull().orEmpty()

    private companion object {
        val MATCH_MAX_AGE: Duration = Duration.ofDays(30)
        val MISS_MAX_AGE: Duration = Duration.ofDays(7)
    }
}
