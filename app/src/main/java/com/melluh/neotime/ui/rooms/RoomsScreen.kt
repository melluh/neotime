package com.melluh.neotime.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import com.melluh.neotime.timeedit.BookingView
import com.melluh.neotime.timeedit.Room
import com.melluh.neotime.timeedit.TIMEEDIT_ZONE
import com.melluh.neotime.timeedit.TimeRange
import com.melluh.neotime.ui.AccountChip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [result] belongs to [building] on [date]; null until the first load for that pair is done. */
data class RoomsUiState(
    val loading: Boolean = true,
    val building: String? = null,
    val date: LocalDate? = null,
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

    private val _date = MutableStateFlow(today())
    val date: StateFlow<LocalDate> = _date

    private val _state = MutableStateFlow(RoomsUiState())
    val state: StateFlow<RoomsUiState> = _state

    private val reload = MutableStateFlow(0)
    private var loadedAt: Instant? = null

    init {
        viewModelScope.launch {
            _booking.value = container.rooms.bookingView()
            val last = container.rooms.lastBuilding.first()
            _building.value = last?.takeIf { it in _booking.value.buildings } ?: _booking.value.buildings.first()
        }
        viewModelScope.launch {
            // A login, or losing the session, changes what this screen can show
            val signedIn = container.session.state
                .map { it !is SessionState.LoggedOut && it !is SessionState.NeedsInteractiveLogin }
                .distinctUntilChanged()
            combine(_building, _date, reload, signedIn) { building, date, _, _ -> building to date }
                .collectLatest { (building, date) ->
                    if (building == null) return@collectLatest
                    _state.value = _state.value.let {
                        if (it.building == building && it.date == date) it.copy(loading = true)
                        else RoomsUiState(loading = true, building = building, date = date)
                    }
                    val result = container.rooms.availability(building, date)
                    loadedAt = Instant.now()
                    _state.value = RoomsUiState(loading = false, building = building, date = date, result = result)
                }
        }
    }

    fun selectBuilding(building: String) {
        _building.value = building
        viewModelScope.launch { container.rooms.selectBuilding(building) }
    }

    fun stepDay(days: Long) {
        _date.value = _date.value.plusDays(days).coerceIn(today(), today().plusDays(_booking.value.days.toLong()))
    }

    fun goToToday() {
        _date.value = today()
    }

    fun refresh() {
        reload.value++
    }

    /** Free times depend on the clock, so don't show old ones when returning to the app. */
    fun refreshIfStale() {
        if (_date.value.isBefore(today())) {
            goToToday()
        } else if (loadedAt?.let { Duration.between(it, Instant.now()) > MAX_AGE } == true) {
            refresh()
        }
    }

    fun toggleFavorite(room: Room) {
        viewModelScope.launch { container.rooms.toggleFavorite(room) }
    }

    private fun today(): LocalDate = LocalDate.now(TIMEEDIT_ZONE)

    private companion object {
        val MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(container: AppContainer, onOpenSettings: () -> Unit, onLogin: () -> Unit) {
    val vm = viewModel { RoomsViewModel(container) }
    val booking by vm.booking.collectAsStateWithLifecycle()
    val selected by vm.building.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(TIMEEDIT_ZONE)

    LifecycleResumeEffect(vm) {
        vm.refreshIfStale()
        onPauseOrDispose { }
    }

    val onBook: (Room) -> Unit = {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("Booking isn't available yet")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Rooms")
                        Text(
                            dayLabel(date, today),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.stepDay(-1) }, enabled = date.isAfter(today)) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                    }
                    if (date != today) TextButton(onClick = vm::goToToday) { Text("Today") }
                    IconButton(onClick = { vm.stepDay(1) }, enabled = date.isBefore(today.plusDays(booking.days.toLong()))) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next day")
                    }
                    AccountChip(session, onClick = onOpenSettings)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(booking.buildings, key = { it }) { building ->
                    FilterChip(
                        selected = building == selected,
                        onClick = { vm.selectBuilding(building) },
                        label = { Text(building) },
                    )
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            PullToRefreshBox(
                isRefreshing = state.loading && state.result != null,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                RoomList(state, favorites, vm::toggleFavorite, onBook, vm::refresh, onLogin)
            }
        }
    }
}

@Composable
private fun RoomList(
    state: RoomsUiState,
    favorites: List<Room>,
    onToggleFavorite: (Room) -> Unit,
    onBook: (Room) -> Unit,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
) {
    val statuses = (state.result as? RoomsResult.Ok)?.rooms.orEmpty()
    val favoriteIds = favorites.map { it.id }.toSet()
    val inBuilding = statuses.filter { it.room.building == state.building && it.room.id !in favoriteIds }
    val free = inBuilding.filter { it.free.isNotEmpty() }
        .sortedWith(compareByDescending<RoomStatus> { it.freeNow }.thenBy { it.room.name })
    val hidden = inBuilding.size - free.size

    LazyColumn(Modifier.fillMaxSize()) {
        if (state.result is RoomsResult.NeedsLogin) {
            item {
                Notice("Log in to see project rooms. TimeEdit only shows them to UT accounts.") {
                    Button(onClick = onLogin) { Text("Log in") }
                }
            }
        }
        if (state.result is RoomsResult.Failed) {
            item {
                Notice(state.result.message) {
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionHeader("Favorites") }
            items(favorites, key = { "fav-${it.id}" }) { room ->
                RoomRow(
                    room = room,
                    status = statuses.firstOrNull { it.room.id == room.id },
                    known = state.result is RoomsResult.Ok,
                    favorite = true,
                    showBuilding = true,
                    onToggleFavorite = onToggleFavorite,
                    onBook = onBook,
                )
            }
        }
        if (state.result is RoomsResult.Ok) {
            state.building?.let { item { SectionHeader(it) } }
            items(free, key = { it.room.id }) { status ->
                RoomRow(
                    room = status.room,
                    status = status,
                    known = true,
                    favorite = false,
                    showBuilding = false,
                    onToggleFavorite = onToggleFavorite,
                    onBook = onBook,
                )
            }
            if (free.isEmpty() && hidden > 0) {
                item {
                    Text(
                        "No free rooms left here on this day. Try another day or building.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (hidden > 0) {
                item {
                    Text(
                        if (hidden == 1) "1 fully booked room hidden" else "$hidden fully booked rooms hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
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
private fun RoomRow(
    room: Room,
    status: RoomStatus?,
    known: Boolean,
    favorite: Boolean,
    showBuilding: Boolean,
    onToggleFavorite: (Room) -> Unit,
    onBook: (Room) -> Unit,
) {
    val details = listOfNotNull(
        room.building.takeIf { showBuilding },
        room.capacity?.let { if (it == 1) "1 person" else "$it people" },
    ).joinToString(" · ")
    val free = status?.free.orEmpty()
    ListItem(
        headlineContent = { Text(room.name) },
        supportingContent = {
            Column {
                if (details.isNotEmpty()) Text(details)
                Text(
                    when {
                        !known || status == null -> "Availability unknown"
                        free.isEmpty() -> "Fully booked"
                        else -> (if (status.freeNow) "Free now: " else "Free ") + free.joinToString(", ", transform = ::rangeLabel)
                    },
                    color = if (status?.freeNow == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (free.isNotEmpty()) OutlinedButton(onClick = { onBook(room) }) { Text("Book") }
                IconButton(onClick = { onToggleFavorite(room) }) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

private fun dayLabel(date: LocalDate, today: LocalDate): String =
    (if (date == today) "Today · " else "") + date.format(DAY)

private fun rangeLabel(range: TimeRange): String = "${range.start}–${range.end}"
