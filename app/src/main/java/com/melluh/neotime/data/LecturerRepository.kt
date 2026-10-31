package com.melluh.neotime.data

import com.melluh.neotime.timeedit.PeopleDirectory
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
data class LecturerEntry(val match: PersonMatch? = null, val fetchedAtEpochMs: Long = 0)

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
            val entry = LecturerEntry(directory.lookup(name), now())
            _entries.update { it + (name to entry) }
            save()
        } catch (e: IOException) {
            // Offline or site trouble: the row falls back to the search link
        } finally {
            inFlight.remove(name)
        }
    }

    private fun isFresh(entry: LecturerEntry?): Boolean {
        if (entry == null) return false
        val maxAge = if (entry.match != null) MATCH_MAX_AGE else MISS_MAX_AGE
        return Duration.ofMillis(now() - entry.fetchedAtEpochMs) < maxAge
    }

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
