package com.melluh.neotime.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.melluh.neotime.auth.OsirisSession
import com.melluh.neotime.auth.SessionManager
import com.melluh.neotime.auth.SessionState
import com.melluh.neotime.timeedit.NotLoggedInException
import com.melluh.neotime.timeedit.ObjectRef
import com.melluh.neotime.timeedit.ObjectType
import com.melluh.neotime.timeedit.OsirisClient
import com.melluh.neotime.timeedit.OsirisRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** A registered course. [ref] is its TimeEdit object, null when TimeEdit doesn't have it. */
data class Registration(val code: String, val name: String, val block: String?, val ref: ObjectRef?)

data class RegistrationsState(
    val loading: Boolean = false,
    val items: List<Registration> = emptyList(),
    val error: String? = null,
    val loadedAt: Instant? = null,
)

/** The student's OSIRIS course registrations. Personal study data, so it's never written to disk. */
class RegistrationRepository(
    private val client: OsirisClient,
    private val osiris: OsirisSession,
    private val session: SessionManager,
    private val schedule: ScheduleRepository,
    private val dataStore: DataStore<Preferences>,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(RegistrationsState())
    val state: StateFlow<RegistrationsState> = _state.asStateFlow()

    /** The user's consent. Nothing here contacts OSIRIS without it, and it's off until they opt in. */
    val allowed: Flow<Boolean> = dataStore.data.map { it[OSIRIS_ALLOWED] == true }

    /** Whether the course picker should still ask. Answering "no" there stops it for good. */
    val shouldAsk: Flow<Boolean> = dataStore.data.map { it[OSIRIS_ALLOWED] != true && it[OSIRIS_DECLINED] != true }

    suspend fun setAllowed(allowed: Boolean) {
        dataStore.edit { it[OSIRIS_ALLOWED] = allowed }
        if (!allowed) clear()
    }

    suspend fun decline() {
        dataStore.edit { it[OSIRIS_DECLINED] = true }
    }

    /** Consent belongs to the person, so a logout takes it along and the next account gets asked. */
    suspend fun resetConsent() {
        dataStore.edit {
            it.remove(OSIRIS_ALLOWED)
            it.remove(OSIRIS_DECLINED)
        }
        clear()
    }

    suspend fun refresh(force: Boolean = false) {
        if (!allowed.first()) return
        when (session.state.value) {
            SessionState.LoggedOut -> return
            is SessionState.NeedsInteractiveLogin -> {
                _state.update { it.copy(error = SIGN_IN_NEEDED) }
                return
            }
            else -> Unit
        }
        val loadedAt = _state.value.loadedAt
        if (!force && loadedAt != null && Duration.between(loadedAt, Instant.now()) < MAX_AGE) return
        if (!mutex.tryLock()) return
        try {
            _state.update { it.copy(loading = true, error = null) }
            val loaded = load()
            // Logged out or consent withdrawn while loading: the result must not outlive either
            if (session.state.value != SessionState.LoggedOut && allowed.first()) _state.value = loaded else clear()
        } finally {
            mutex.unlock()
        }
    }

    /** Forgets everything; the next sign-in may be a different account. */
    fun clear() {
        osiris.invalidate()
        _state.value = RegistrationsState()
    }

    private suspend fun load(): RegistrationsState = try {
        val registrations = fetch()
        if (registrations == null) {
            _state.value.copy(loading = false, error = SIGN_IN_NEEDED)
        } else {
            val unique = registrations.distinctBy { it.code to it.block }
            RegistrationsState(items = unique.map { it.resolve() }, loadedAt = Instant.now())
        }
    } catch (e: CancellationException) {
        _state.update { it.copy(loading = false) }
        throw e
    } catch (e: IOException) {
        _state.value.copy(loading = false, error = "Couldn't reach OSIRIS. Check your connection.")
    } catch (e: Exception) {
        _state.value.copy(loading = false, error = "Couldn't load your registrations from OSIRIS.")
    }

    /** Null when no token can be had without the user. A dead token gets one fresh sign-in. */
    private suspend fun fetch(): List<OsirisRegistration>? {
        repeat(2) {
            val token = osiris.token() ?: return null
            try {
                return client.registrations(token)
            } catch (e: NotLoggedInException) {
                osiris.invalidate()
            }
        }
        return null
    }

    private suspend fun OsirisRegistration.resolve() =
        Registration(code, name, block, matchIn(schedule.search(ObjectType.COURSE, code)))

    private companion object {
        val OSIRIS_ALLOWED = booleanPreferencesKey("osiris_allowed")
        val OSIRIS_DECLINED = booleanPreferencesKey("osiris_declined")
        const val SIGN_IN_NEEDED = "Couldn't sign in to OSIRIS automatically. Logging in again in Settings usually fixes this."
        val MAX_AGE: Duration = Duration.ofMinutes(15)
    }
}
