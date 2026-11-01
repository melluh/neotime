package com.melluh.neotime.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melluh.neotime.AppContainer
import com.melluh.neotime.auth.SessionState
import com.melluh.neotime.data.RoomStatus
import com.melluh.neotime.data.RoomsResult
import com.melluh.neotime.timeedit.Availability
import com.melluh.neotime.timeedit.BookingView
import com.melluh.neotime.timeedit.Room
import com.melluh.neotime.timeedit.RoomNames
import com.melluh.neotime.timeedit.TIMEEDIT_ZONE
import com.melluh.neotime.timeedit.TimeRange
import com.melluh.neotime.ui.AccountChip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class RoomWhen { NOW, LATER, OTHER_DAY }

/** What the user is looking for. Only [date] needs a fetch; the rest narrows down what's loaded. */
data class RoomFilter(
    val mode: RoomWhen = RoomWhen.NOW,
    val date: LocalDate,
    /** The picked start; ignored for [RoomWhen.NOW]. */
    val start: LocalTime = DEFAULT_START,
    val minutes: Int = 60,
    /** A room size in the building; null for any. */
    val size: Int? = null,
    val type: String? = null,
) {
    /** The time to book as of [now]; null when today is over and today was asked for. */
    fun window(now: LocalDateTime): TimeRange? {
        val first = Availability.firstStart(now.toLocalTime())
        val from = when (mode) {
            RoomWhen.NOW -> first
            RoomWhen.LATER -> first?.let { maxOf(it, start) }
            RoomWhen.OTHER_DAY -> start
        }
        return from?.let { Availability.window(it, minutes.toLong()) }
    }

    fun accepts(room: Room): Boolean =
        (size == null || room.capacity == null || room.capacity == size) && (type == null || room.type == type)

    /** Moves off days and modes that [now] has made impossible. */
    fun settled(now: LocalDateTime): RoomFilter {
        val today = now.toLocalDate()
        return when {
            Availability.firstStart(now.toLocalTime()) == null ->
                if (date.isAfter(today)) this else copy(mode = RoomWhen.OTHER_DAY, date = today.plusDays(1))
            date.isBefore(today) || (mode == RoomWhen.OTHER_DAY && date == today) ->
                copy(mode = RoomWhen.NOW, date = today)
            else -> this
        }
    }

    companion object {
        val DEFAULT_START: LocalTime = LocalTime.of(10, 0)
    }
}

/** [result] belongs to [building] on [date], as of [now]; null until the first load for that pair is done. */
data class RoomsUiState(
    val loading: Boolean = true,
    val building: String? = null,
    val date: LocalDate? = null,
    val now: LocalDateTime,
    val result: RoomsResult? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsViewModel(private val container: AppContainer) : ViewModel() {
    val favorites: StateFlow<List<Room>> = container.rooms.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _booking = MutableStateFlow(BookingView.FALLBACK)
    val booking: StateFlow<BookingView> = _booking

    private val _building = MutableStateFlow<String?>(null)
    val building: StateFlow<String?> = _building

    private val _filter = MutableStateFlow(now().let { RoomFilter(date = it.toLocalDate()).settled(it) })
    val filter: StateFlow<RoomFilter> = _filter

    private val _state = MutableStateFlow(RoomsUiState(now = now()))
    val state: StateFlow<RoomsUiState> = _state

    /** A reload the user asked for; the ones the clock triggers stay out of sight. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** A room another tab sent here, to point out once it's loaded. */
    private val _focused = MutableStateFlow<Room?>(null)
    val focused: StateFlow<Room?> = _focused

    private val reload = MutableStateFlow(0)
    private var loadedAt: Instant? = null
    private val booked: Job

    init {
        booked = viewModelScope.launch {
            _booking.value = container.rooms.bookingView()
            val last = container.rooms.lastBuilding.first()
            _building.value = last?.takeIf { it in _booking.value.buildings } ?: _booking.value.buildings.first()
        }
        viewModelScope.launch {
            // A login, or losing the session, changes what this screen can show
            val signedIn = container.session.state
                .map { it !is SessionState.LoggedOut && it !is SessionState.NeedsInteractiveLogin }
                .distinctUntilChanged()
            val dates = _filter.map { it.date }.distinctUntilChanged()
            combine(_building, dates, reload, signedIn) { building, date, _, _ -> building to date }
                .collectLatest { (building, date) ->
                    if (building == null) return@collectLatest
                    _state.value = _state.value.let {
                        if (it.building == building && it.date == date) it.copy(loading = true)
                        else RoomsUiState(loading = true, building = building, date = date, now = it.now)
                    }
                    // One clock for the free times and the window they're compared to
                    val now = now()
                    val result = container.rooms.availability(building, date, now)
                    loadedAt = Instant.now()
                    _state.value = RoomsUiState(false, building, date, now, result)
                    _refreshing.value = false
                }
        }
        viewModelScope.launch {
            container.focus.inRooms.filterNotNull().collect { room ->
                // After the startup job picks a building, or it would overwrite this one
                booked.join()
                room.building?.let(::selectBuilding)
                // A room that doesn't fit the filter wouldn't be listed
                _filter.update { it.copy(size = null, type = null) }
                _focused.value = room
                container.focus.clearRooms()
            }
        }
    }

    fun clearFocused() {
        _focused.value = null
    }

    fun selectBuilding(building: String) {
        _building.value = building
        _filter.update { it.copy(size = null, type = null) }
        viewModelScope.launch { container.rooms.selectBuilding(building) }
    }

    fun selectNow() {
        _filter.update { it.copy(mode = RoomWhen.NOW, date = now().toLocalDate()) }
    }

    fun selectLater() {
        val now = now()
        val first = Availability.firstStart(now.toLocalTime()) ?: return
        val start = minOf(first.plusHours(1), Availability.LAST_START)
        _filter.update { it.copy(mode = RoomWhen.LATER, date = now.toLocalDate(), start = start) }
    }

    fun selectDay(date: LocalDate) {
        _filter.update {
            it.copy(
                mode = RoomWhen.OTHER_DAY,
                date = date,
                start = if (it.mode == RoomWhen.NOW) RoomFilter.DEFAULT_START else it.start,
            )
        }
    }

    fun selectStart(start: LocalTime) = _filter.update { it.copy(start = start) }

    fun selectMinutes(minutes: Int) = _filter.update { it.copy(minutes = minutes) }

    fun selectSize(size: Int?) = _filter.update { it.copy(size = size) }

    fun selectType(type: String?) = _filter.update { it.copy(type = type) }

    fun refresh() {
        _refreshing.value = _building.value != null
        reload.value++
    }

    /** Free times depend on the clock, so don't keep showing old ones. */
    fun refreshIfStale() {
        val date = _filter.value.date
        _filter.update { it.settled(now()) }
        val stale = loadedAt?.let { Duration.between(it, Instant.now()) > MAX_AGE } == true
        if (_filter.value.date == date && stale) reload.value++
    }

    fun toggleFavorite(room: Room) {
        viewModelScope.launch { container.rooms.toggleFavorite(room) }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(TIMEEDIT_ZONE)

    private companion object {
        val MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(container: AppContainer, onOpenSettings: () -> Unit, onLogin: () -> Unit, onOpenMap: () -> Unit) {
    val vm = viewModel { RoomsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(vm) {
        val clock = scope.launch {
            while (true) {
                vm.refreshIfStale()
                delay(CLOCK_CHECK_MS)
            }
        }
        onPauseOrDispose { clock.cancel() }
    }

    val onBook: (Room) -> Unit = {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("Booking isn't available yet")
        }
    }

    val onShowOnMap: (Room) -> Unit = { room ->
        scope.launch {
            val problem = container.focus.showOnMap(room.name)
            if (problem == null) {
                onOpenMap()
            } else {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(problem)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rooms") },
                actions = { AccountChip(session, onClick = onOpenSettings) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading && state.result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val signedIn = session !is SessionState.LoggedOut && session !is SessionState.NeedsInteractiveLogin
                when {
                    state.result is RoomsResult.NeedsLogin -> LoginPrompt(onLogin)
                    // The session reads as logged out until it's restored, so wait for the result to tell
                    state.result == null && !signedIn -> Unit
                    else -> RoomFinder(vm, onBook, onShowOnMap)
                }
            }
        }
    }
}

/** All there is to do without a session; a list so it can still be pulled to refresh. */
@Composable
private fun LoginPrompt(onLogin: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Notice("You need to log in to book project rooms.") {
                Button(onClick = onLogin) { Text("Log in") }
            }
        }
    }
}

/** The questions in the order you'd ask them, with the rooms that fit below. */
@Composable
private fun RoomFinder(vm: RoomsViewModel, onBook: (Room) -> Unit, onShowOnMap: (Room) -> Unit) {
    val booking by vm.booking.collectAsStateWithLifecycle()
    val selected by vm.building.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    var showTaken by rememberSaveable { mutableStateOf(false) }

    val window = filter.window(state.now)
    val fits = { status: RoomStatus? -> status != null && window != null && Availability.isFree(status.free, window) }

    val statuses = (state.result as? RoomsResult.Ok)?.rooms.orEmpty()
    val here = statuses.filter { it.room.building == state.building }
    val sizes = here.mapNotNull { it.room.capacity }.distinct().sorted()
    val types = here.mapNotNull { it.room.type }.distinct().sorted()
    val favoriteIds = favorites.map { it.id }.toSet()
    val matching = here.filter { it.room.id !in favoriteIds && filter.accepts(it.room) }
    val (free, taken) = matching.partition(fits)
    val onToday = state.date == state.now.toLocalDate()
    val past = state.now.toLocalTime().takeIf { onToday }

    val focused by vm.focused.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val headerPx = with(LocalDensity.current) { STICKY_HEADER_HEIGHT.roundToPx() }
    LaunchedEffect(focused, state) {
        val room = focused ?: return@LaunchedEffect
        if (state.loading || state.building != room.building) return@LaunchedEffect
        if (taken.any { it.room.id == room.id }) showTaken = true
        val favoriteRows = if (favorites.isEmpty()) 0 else favorites.size + 1
        // The questions and sticky header, then these sections in the order the list has them
        val firstRoom = QUESTIONS + 1 + favoriteRows + 1
        val hint = if (free.isEmpty() && taken.isNotEmpty()) 1 else 0
        val index = when {
            room.id in favoriteIds -> QUESTIONS + 2 + favorites.indexOfFirst { it.id == room.id }
            free.any { it.room.id == room.id } -> firstRoom + free.indexOfFirst { it.room.id == room.id }
            taken.any { it.room.id == room.id } -> firstRoom + hint + 1 + taken.indexOfFirst { it.room.id == room.id }
            else -> null
        }
        if (index != null) {
            list.animateScrollToItem(index, -headerPx)
            delay(FOCUS_MS)
        }
        vm.clearFocused()
    }

    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item {
            Question("Where") {
                ChoiceChips(booking.buildings, selected, { it.orEmpty() }, onSelect = { it?.let(vm::selectBuilding) })
            }
        }
        item {
            Question("When") { WhenChips(filter, state.now, booking, vm) }
        }
        // Stays an item when there's nothing to ask, as QUESTIONS counts it
        item {
            if (sizes.size > 1 || types.size > 1) {
                Question("Who") {
                    if (sizes.size > 1) {
                        ChoiceChips(listOf(null) + sizes, filter.size, { it?.let(::peopleLabel) ?: "Any size" }, onSelect = vm::selectSize)
                    }
                    if (types.size > 1) {
                        ChoiceChips(listOf(null) + types, filter.type, { it ?: "Any type" }, onSelect = vm::selectType)
                    }
                }
            }
        }
        if (state.result is RoomsResult.Failed) {
            item {
                Notice((state.result as RoomsResult.Failed).message) {
                    OutlinedButton(onClick = vm::refresh) { Text("Retry") }
                }
            }
        }
        if (state.result is RoomsResult.Ok) {
            stickyHeader {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        listOfNotNull(state.date?.let { dayLabel(it, state.now.toLocalDate()) }, window?.let(::rangeLabel))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    BarLegend(showPast = onToday)
                    TimeAxis()
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionHeader("Favorites") }
            items(favorites, key = { "fav-${it.id}" }) { room ->
                val status = statuses.firstOrNull { it.room.id == room.id }
                RoomRow(room, status, window, past, favorite = true, showBuilding = true, fits(status), room.id == focused?.id, vm::toggleFavorite, onBook, onShowOnMap)
            }
        }
        if (state.result is RoomsResult.Ok) {
            state.building?.let { item { SectionHeader(it) } }
            items(free, key = { it.room.id }) { status ->
                RoomRow(status.room, status, window, past, favorite = false, showBuilding = false, true, status.room.id == focused?.id, vm::toggleFavorite, onBook, onShowOnMap)
            }
            if (matching.isEmpty() && here.size > favorites.count { it.building == state.building }) {
                item { Hint("No rooms here that fit. Try another size or building.") }
            } else if (free.isEmpty() && taken.isNotEmpty()) {
                item { Hint("Nothing free for that time. Try another time or building.") }
            }
            if (taken.isNotEmpty()) {
                item {
                    TextButton(onClick = { showTaken = !showTaken }, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(if (taken.size == 1) "1 room taken at that time" else "${taken.size} rooms taken at that time")
                        Icon(if (showTaken) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    }
                }
            }
            if (showTaken) {
                items(taken, key = { it.room.id }) { status ->
                    RoomRow(status.room, status, window, past, favorite = false, showBuilding = false, false, status.room.id == focused?.id, vm::toggleFavorite, onBook, onShowOnMap)
                }
            }
        }
    }
}

@Composable
private fun Question(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        content()
    }
}

@Composable
private fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    val list = rememberLazyListState()
    // A selection further along the row, like a remembered building, shouldn't start out of view
    LaunchedEffect(selected) {
        val index = options.indexOf(selected)
        if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index && it.offset + it.size <= list.layoutInfo.viewportEndOffset }) {
            list.animateScrollToItem(index)
        }
    }
    LazyRow(
        state = list,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(options) { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                enabled = enabled(option),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhenChips(filter: RoomFilter, now: LocalDateTime, booking: BookingView, vm: RoomsViewModel) {
    var pickingDay by rememberSaveable { mutableStateOf(false) }
    val today = now.toLocalDate()
    val first = Availability.firstStart(now.toLocalTime())

    ChoiceChips(
        RoomWhen.entries,
        filter.mode,
        label = {
            when (it) {
                RoomWhen.NOW -> "Now"
                RoomWhen.LATER -> "Later today"
                RoomWhen.OTHER_DAY -> if (filter.mode == it) filter.date.format(DAY) else "Another day"
            }
        },
        enabled = {
            when (it) {
                RoomWhen.NOW -> first != null
                RoomWhen.LATER -> first != null && first.isBefore(Availability.LAST_START)
                RoomWhen.OTHER_DAY -> booking.days > 0
            }
        },
        onSelect = {
            when (it) {
                RoomWhen.NOW -> vm.selectNow()
                RoomWhen.LATER -> vm.selectLater()
                RoomWhen.OTHER_DAY -> pickingDay = true
            }
        },
    )
    if (filter.mode != RoomWhen.NOW) {
        val earliest = if (filter.mode == RoomWhen.LATER) first else Availability.OPEN
        filter.window(now)?.let { window ->
            if (earliest != null) StartSlider(window.start, earliest, vm::selectStart)
        }
    }
    ChoiceChips(DURATIONS, filter.minutes, ::durationLabel, onSelect = vm::selectMinutes)

    if (pickingDay) {
        DayDialog(
            selected = filter.date.takeIf { it.isAfter(today) },
            first = today.plusDays(1),
            last = today.plusDays(booking.days.toLong()),
            onPick = {
                pickingDay = false
                vm.selectDay(it)
            },
            onDismiss = { pickingDay = false },
        )
    }
}

@Composable
private fun StartSlider(start: LocalTime, earliest: LocalTime, onChange: (LocalTime) -> Unit) {
    val from = earliest.toSecondOfDay() / 60f
    val to = Availability.LAST_START.toSecondOfDay() / 60f
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("From $start", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = start.toSecondOfDay() / 60f,
            // Snapped here instead of with steps, which would draw 58 tick marks
            onValueChange = {
                val slots = ((it - from) / Availability.STEP_MINUTES).roundToInt()
                onChange(earliest.plusMinutes(slots * Availability.STEP_MINUTES))
            },
            valueRange = from..to,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDialog(
    selected: LocalDate?,
    first: LocalDate,
    last: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // The picker works in UTC milliseconds, whatever the device's zone is
    fun toMillis(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun toDate(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    val picker = rememberDatePickerState(
        initialSelectedDateMillis = selected?.let(::toMillis),
        initialDisplayedMonthMillis = toMillis(selected ?: first),
        selectableDates = remember(first, last) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = toDate(utcTimeMillis) in first..last
                override fun isSelectableYear(year: Int) = year in first.year..last.year
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { picker.selectedDateMillis?.let { onPick(toDate(it)) } },
                enabled = picker.selectedDateMillis != null,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(picker, showModeToggle = false)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Notice(message: String, action: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}

@Composable
private fun Hint(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
}

@Composable
private fun RoomRow(
    room: Room,
    status: RoomStatus?,
    window: TimeRange?,
    past: LocalTime?,
    favorite: Boolean,
    showBuilding: Boolean,
    bookable: Boolean,
    highlighted: Boolean,
    onToggleFavorite: (Room) -> Unit,
    onBook: (Room) -> Unit,
    onShowOnMap: (Room) -> Unit,
) {
    val details = listOfNotNull(
        room.building.takeIf { showBuilding },
        room.capacity?.let(::peopleLabel),
    ).joinToString(" · ")
    val background = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column(Modifier.fillMaxWidth().background(background).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(room.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (bookable) {
                OutlinedButton(
                    onClick = { onBook(room) },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(36.dp),
                ) { Text("Book") }
            }
            if (RoomNames.identifierCandidates(room.name).isNotEmpty()) {
                IconButton(onClick = { onShowOnMap(room) }) {
                    Icon(Icons.Filled.LocationOn, contentDescription = "Show on map", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { onToggleFavorite(room) }) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (status != null) {
            AvailabilityBar(status, window, past)
        } else {
            Text(
                "Availability unknown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val CLOCK_CHECK_MS = 60_000L
private const val FOCUS_MS = 3_000L

/** Where, When and Who, ahead of the sticky header. */
private const val QUESTIONS = 3
private val STICKY_HEADER_HEIGHT = 96.dp

private val DURATIONS = listOf(30, 60, 120, 180, 240)

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

private fun dayLabel(date: LocalDate, today: LocalDate): String =
    (if (date == today) "Today · " else "") + date.format(DAY)

private fun durationLabel(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h"

private fun peopleLabel(people: Int): String = if (people == 1) "1 person" else "$people people"
