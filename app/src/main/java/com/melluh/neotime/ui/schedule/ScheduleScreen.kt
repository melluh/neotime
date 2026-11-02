package com.melluh.neotime.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melluh.neotime.AppContainer
import com.melluh.neotime.data.LecturerEntry
import com.melluh.neotime.data.RefreshResult
import com.melluh.neotime.data.ScheduleSnapshot
import com.melluh.neotime.timeedit.DataSource
import com.melluh.neotime.timeedit.Event
import com.melluh.neotime.timeedit.ObjectRef
import com.melluh.neotime.timeedit.PersonMatch
import com.melluh.neotime.timeedit.RoomNames
import com.melluh.neotime.timeedit.RoomPlace
import com.melluh.neotime.timeedit.TIMEEDIT_ZONE
import com.melluh.neotime.timeedit.TimeEditException
import com.melluh.neotime.timeedit.isPlaceholderLecturer
import com.melluh.neotime.timeedit.visible
import com.melluh.neotime.ui.AccountChip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class ScheduleViewModel(private val container: AppContainer) : ViewModel() {
    val snapshot: StateFlow<ScheduleSnapshot> = container.schedule.snapshot
    val selection: StateFlow<List<ObjectRef>?> = container.schedule.selection
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val lecturers: StateFlow<Map<String, LecturerEntry>> = container.lecturers.entries

    /** The schedule's events minus the course parts the user hid. */
    val visibleEvents: StateFlow<List<Event>> = combine(snapshot, container.schedule.hiddenParts) { snap, hidden ->
        snap.events.visible(hidden)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val refreshing: StateFlow<Boolean> = container.schedule.refreshing

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages

    init {
        // A fresh cache skips the refresh below, so check the restored login here: an expired one
        // has to show up at launch, and a public cache shouldn't outlive a session that still works
        viewModelScope.launch {
            val active = try {
                container.session.ensureSession()
            } catch (e: TimeEditException) {
                false
            }
            if (active && snapshot.value.source == DataSource.PUBLIC) refresh()
        }
        // Refresh when the selection no longer matches the cache, or the cache is old
        viewModelScope.launch {
            combine(container.schedule.selection, snapshot) { sel, snap -> sel.map { it.id }.sorted() to snap }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (ids, snap) ->
                    val stale = snap.fetchedAt?.let { Duration.between(it, Instant.now()) > MAX_AGE } ?: true
                    if (ids != snap.objects.sorted() || stale) refresh()
                }
        }
    }

    /** Skipping is safe: a running refresh picks up selection changes before it finishes. */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            val result = container.schedule.refresh()
            if (result is RefreshResult.Failed) _messages.value = result.message
        }
    }

    fun loadLecturers(names: List<String>) {
        viewModelScope.launch { container.lecturers.load(names) }
    }

    /** Returns why [place] can't be shown, or null if the Map tab can open it. */
    suspend fun showOnMap(place: RoomPlace): String? = container.focus.showOnMap(place.name, place.identifier)

    fun messageShown() {
        _messages.value = null
    }

    private companion object {
        val MAX_AGE: Duration = Duration.ofHours(6)
    }
}

private const val WEEK_PAGES = 200
private const val CURRENT_WEEK_PAGE = WEEK_PAGES / 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(container: AppContainer, onOpenPicker: () -> Unit, onOpenSettings: () -> Unit, onOpenMap: () -> Unit) {
    val vm = viewModel { ScheduleViewModel(container) }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val visibleEvents by vm.visibleEvents.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()

    val thisMonday = remember { LocalDate.now(TIMEEDIT_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val pager = rememberPagerState(initialPage = CURRENT_WEEK_PAGE) { WEEK_PAGES }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<Event?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val shownMonday = thisMonday.plusWeeks((pager.currentPage - CURRENT_WEEK_PAGE).toLong())
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Week ${shownMonday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}")
                        Text(
                            weekRange(shownMonday),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
                    }
                    if (pager.currentPage != CURRENT_WEEK_PAGE) {
                        TextButton(onClick = { scope.launch { pager.animateScrollToPage(CURRENT_WEEK_PAGE) } }) { Text("Today") }
                    }
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next week")
                    }
                    IconButton(onClick = onOpenPicker) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Courses and groups") }
                    AccountChip(session, onClick = onOpenSettings)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (selection?.isEmpty() == true) {
                EmptyState(onOpenPicker)
                return@Column
            }
            if (snapshot.source == DataSource.PUBLIC && snapshot.events.isNotEmpty()) PublicDataNotice(onOpenSettings)
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    val monday = thisMonday.plusWeeks((page - CURRENT_WEEK_PAGE).toLong())
                    val sunday = monday.plusDays(6)
                    val weekEvents = remember(visibleEvents, monday) {
                        visibleEvents.filter { !it.date.isBefore(monday) && !it.date.isAfter(sunday) }
                    }
                    WeekGrid(monday = monday, events = weekEvents, onEventClick = { selected = it })
                }
            }
        }
    }

    selected?.let { event ->
        EventSheet(
            event,
            vm,
            container,
            showLecturerHint = snapshot.source == DataSource.PUBLIC,
            onOpenMap = {
                selected = null
                onOpenMap()
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun PublicDataNotice(onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, onClick = onOpenSettings) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                "You're seeing the limited public schedule. Log in to view the full schedule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun EmptyState(onOpenPicker: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No courses yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add your courses or student group to see your timetable.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenPicker) { Text("Add courses or groups") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventSheet(
    event: Event,
    vm: ScheduleViewModel,
    container: AppContainer,
    showLecturerHint: Boolean,
    onOpenMap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mapProblem by remember(event) { mutableStateOf<String?>(null) }
    var openedLecturer by remember(event) { mutableStateOf<String?>(null) }
    val places = event.places.filter { it.identifier != null || RoomNames.identifierCandidates(it.name).isNotEmpty() }
    val lecturers by vm.lecturers.collectAsStateWithLifecycle()
    LaunchedEffect(event) { vm.loadLecturers(event.lecturers) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(event.courseName, style = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(event.activityType, if (event.resit) "Resit" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Detail("When", "${event.start.format(DAY_FORMAT)}, ${event.start.toLocalTime()} – ${event.end.toLocalTime()}")
            Detail("Where", if (event.rooms.isEmpty()) "Not published yet" else event.rooms.joinToString(", "))
            if (event.lecturers.isEmpty()) {
                Detail("Lecturers", if (showLecturerHint) "Log in to see lecturers" else "None listed")
            } else {
                Column {
                    DetailLabel("Lecturers")
                    event.lecturers.forEach { name ->
                        LecturerRow(name, lecturers[name]?.on(event)?.match) { openedLecturer = name }
                    }
                }
            }
            event.coursePartName?.let { Detail("Part", it) }
            event.extraInfo?.let { Detail("Info", it) }
            places.forEach { place ->
                Button(
                    onClick = {
                        scope.launch {
                            mapProblem = vm.showOnMap(place)
                            if (mapProblem == null) onOpenMap()
                        }
                    },
                ) { Text(if (places.size > 1) "Show ${RoomNames.canonical(place.name)} on map" else "Show on map") }
            }
            mapProblem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        openedLecturer?.let { name ->
            LecturerCard(name, lecturers[name]?.on(event), container, onOpenMap, onDismiss = { openedLecturer = null })
        }
    }
}

@Composable
internal fun LecturerRow(name: String, match: PersonMatch?, onClick: () -> Unit) {
    if (isPlaceholderLecturer(name)) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        return
    }
    val displayName = match?.displayName ?: name
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Show details of $displayName", onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        LecturerAvatar(displayName, listOfNotNull(match?.photoUrl), size = 40.dp, initialsStyle = MaterialTheme.typography.labelLarge)
        Text(displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        DetailLabel(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun DetailLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

private fun weekRange(monday: LocalDate): String {
    val sunday = monday.plusDays(6)
    val day = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    return "${monday.format(day)} – ${sunday.format(day)}"
}
