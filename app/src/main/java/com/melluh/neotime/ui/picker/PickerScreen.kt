package com.melluh.neotime.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melluh.neotime.AppContainer
import com.melluh.neotime.auth.SessionState
import com.melluh.neotime.data.RefreshResult
import com.melluh.neotime.data.Registration
import com.melluh.neotime.data.RegistrationsState
import com.melluh.neotime.timeedit.CoursePart
import com.melluh.neotime.timeedit.ObjectRef
import com.melluh.neotime.timeedit.ObjectType
import com.melluh.neotime.timeedit.courseParts
import com.melluh.neotime.ui.settings.OSIRIS_CONSENT_EXPLANATION
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MIN_QUERY_LENGTH = 2

data class SearchState(val loading: Boolean = false, val results: List<ObjectRef> = emptyList(), val error: String? = null)

@OptIn(FlowPreview::class)
class PickerViewModel(private val container: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val type = MutableStateFlow(ObjectType.STUDENT_SET)
    val selection: StateFlow<List<ObjectRef>> = container.schedule.selection
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    init {
        viewModelScope.launch {
            combine(query.debounce(400), type) { q, t -> q.trim() to t }.collect { (q, t) ->
                if (q.length < MIN_QUERY_LENGTH) {
                    _search.value = SearchState()
                    return@collect
                }
                _search.value = _search.value.copy(loading = true, error = null)
                _search.value = try {
                    SearchState(results = container.schedule.search(t, q))
                } catch (e: Exception) {
                    SearchState(error = "Search failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Runs in the app scope: leaving the screen shouldn't throw away a sign-in that's nearly done. */
    fun refreshRegistrations(force: Boolean = false) {
        container.scope.launch { container.registrations.refresh(force) }
    }

    fun allowOsiris() {
        container.scope.launch { container.registrations.setAllowed(true) }
    }

    fun declineOsiris() {
        container.scope.launch { container.registrations.decline() }
    }

    fun toggle(ref: ObjectRef) = viewModelScope.launch {
        val before = selection.value
        if (before.any { it.id == ref.id }) {
            container.schedule.remove(ref)
        } else {
            container.schedule.add(ref)
            askForParts(listOf(ref), before)
        }
    }

    fun addAll(refs: List<ObjectRef>) {
        viewModelScope.launch {
            val before = selection.value
            container.schedule.addAll(refs)
            askForParts(refs, before)
        }
    }

    private val queuedPrompts = MutableStateFlow<List<PartsPrompt>>(emptyList())

    /** The next added course to ask about. Dropped once the course is off the schedule again. */
    internal val partsPrompt: StateFlow<PartsPrompt?> = combine(queuedPrompts, selection) { prompts, sel ->
        prompts.firstOrNull { prompt -> sel.any { it.id == prompt.ref.id } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Parts only show up in the schedule's events, so this waits for the fetch that includes [added].
     * A course whose code was already on the schedule through another block keeps the choice made then.
     */
    private suspend fun askForParts(added: List<ObjectRef>, before: List<ObjectRef>) {
        val courses = added
            .filter { it.type == ObjectType.COURSE && before.none { b -> b.id == it.id } }
            .mapNotNull { ref -> ref.courseCode?.let { ref to it } }
            .filter { (_, code) -> before.none { it.courseCode == code } }
            .distinctBy { (_, code) -> code }
        if (courses.isEmpty()) return
        if (container.schedule.ensureFetched() !is RefreshResult.Ok) return
        val events = container.schedule.snapshot.value.events
        val prompts = courses
            .map { (ref, code) -> PartsPrompt(ref, code, courseParts(code, events).names) }
            .filter { it.parts.isNotEmpty() }
        queuedPrompts.update { it + prompts }
    }

    /** Takes the parts the user left unticked off the schedule. */
    internal fun answer(prompt: PartsPrompt, shown: Set<String>) {
        queuedPrompts.update { it - prompt }
        val hidden = prompt.parts.filterNot { it in shown }.map { CoursePart(prompt.code, it) }
        container.scope.launch { container.schedule.hideParts(hidden) }
    }
}

/** A course just added that has parts, for the user to choose which of them to keep. */
internal data class PartsPrompt(val ref: ObjectRef, val code: String, val parts: List<String>)

private val PICKABLE = listOf(ObjectType.STUDENT_SET, ObjectType.COURSE, ObjectType.STUDY_PROGRAMME)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PickerScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = viewModel { PickerViewModel(container) }
    val query by vm.query.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val registrations by container.registrations.state.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()
    val showRegistrations = session !is SessionState.LoggedOut && query.trim().length < MIN_QUERY_LENGTH

    val osirisAllowed by container.registrations.allowed.collectAsStateWithLifecycle(initialValue = null)
    val askForOsiris by container.registrations.shouldAsk.collectAsStateWithLifecycle(initialValue = false)
    var showDeclinedNotice by rememberSaveable { mutableStateOf(false) }

    if (showDeclinedNotice) DeclinedNotice(onDismiss = { showDeclinedNotice = false })

    val partsPrompt by vm.partsPrompt.collectAsStateWithLifecycle()
    partsPrompt?.let { prompt -> PartsDialog(prompt, onDone = { shown -> vm.answer(prompt, shown) }) }

    LaunchedEffect(session is SessionState.LoggedOut, osirisAllowed) {
        if (osirisAllowed == true) vm.refreshRegistrations()
    }

    val listState = rememberLazyListState()
    LaunchedEffect(query.isEmpty()) {
        if (query.isEmpty()) listState.scrollToItem(0)
    }

    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (!imeVisible) focusManager.clearFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add courses") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = query.takeIf { it.isNotEmpty() }?.let {
                    {
                        IconButton(onClick = { vm.query.value = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search here") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (query.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    for (t in PICKABLE) {
                        FilterChip(selected = type == t, onClick = { vm.type.value = t }, label = { Text(t.label) })
                    }
                }
            }
            if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            search.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (showRegistrations && askForOsiris) {
                    item(key = "registrations-consent") {
                        RegistrationsConsent(
                            onAllow = vm::allowOsiris,
                            onDecline = {
                                vm.declineOsiris()
                                showDeclinedNotice = true
                            },
                        )
                    }
                }
                if (showRegistrations && osirisAllowed == true) {
                    item(key = "registrations-header") {
                        val addable = registrations.items.mapNotNull { it.ref }.filter { ref -> selection.none { it.id == ref.id } }
                        RegistrationsHeader(
                            state = registrations,
                            onRetry = { vm.refreshRegistrations(force = true) },
                            onAddAll = { vm.addAll(addable) }.takeIf { addable.isNotEmpty() && !registrations.loading },
                        )
                    }
                    items(registrations.items, key = { "registration-${it.code}-${it.block}" }) { registration ->
                        val ref = registration.ref
                        ListItem(
                            headlineContent = { Text(registration.name) },
                            supportingContent = { Text(registration.subtitle()) },
                            trailingContent = ref?.let {
                                { ToggleButton(added = selection.any { it.id == ref.id }, onClick = { vm.toggle(ref) }) }
                            },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                items(search.results, key = { it.id }) { ref ->
                    ListItem(
                        headlineContent = { Text(ref.name.substringBefore(',')) },
                        supportingContent = ref.name.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                        trailingContent = {
                            ToggleButton(added = selection.any { it.id == ref.id }, onClick = { vm.toggle(ref) })
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

private fun Registration.subtitle(): String =
    if (ref == null) "$code · Not on TimeEdit" else listOfNotNull(code, block).joinToString(" · ")

@Composable
private fun ToggleButton(added: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(if (added) Icons.Filled.Check else Icons.Filled.Add, contentDescription = if (added) "Remove" else "Add")
    }
}

/** Everything starts ticked; closing the dialog any way keeps what's ticked at that point. */
@Composable
private fun PartsDialog(prompt: PartsPrompt, onDone: (shown: Set<String>) -> Unit) {
    var shown by rememberSaveable(prompt.ref.id) { mutableStateOf(prompt.parts.toSet()) }
    AlertDialog(
        onDismissRequest = { onDone(shown) },
        title = { Text(prompt.ref.name.substringBefore(',')) },
        text = {
            Column {
                Text("Which parts of this course do you want on your schedule?")
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(prompt.parts, key = { it }) { part ->
                        val checked = part in shown
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = checked, role = Role.Checkbox) {
                                    shown = if (it) shown + part else shown - part
                                },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            Text(part, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(shown) }) { Text("Done") }
        },
    )
}

@Composable
private fun DeclinedNotice(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text("This won't show again. No data will be read from OSIRIS. If you want, you can still enable this feature later from the settings page.") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

@Composable
private fun RegistrationsConsent(onAllow: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Your current OSIRIS registrations", style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Allow fetching from OSIRIS?", style = MaterialTheme.typography.titleMedium)
                Text(OSIRIS_CONSENT_EXPLANATION, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAllow) { Text("Allow") }
                    OutlinedButton(onClick = onDecline) { Text("No") }
                }
            }
        }
    }
}

@Composable
private fun RegistrationsHeader(state: RegistrationsState, onRetry: () -> Unit, onAddAll: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your current OSIRIS registrations", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (onAddAll != null) {
                TextButton(
                    onClick = onAddAll,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("Add all", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            state.error != null -> {
                Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
            }
            state.loadedAt != null && state.items.isEmpty() -> Text(
                "OSIRIS lists no current course registrations.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
