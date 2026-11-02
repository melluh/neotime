package com.melluh.neotime.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melluh.neotime.AppContainer
import com.melluh.neotime.auth.SessionState
import com.melluh.neotime.data.MapResult
import com.melluh.neotime.data.MapSelection
import com.melluh.neotime.data.RoomsResult
import com.melluh.neotime.timeedit.GeoPoint
import com.melluh.neotime.timeedit.MapBuilding
import com.melluh.neotime.timeedit.MapEntrance
import com.melluh.neotime.timeedit.MapFeature
import com.melluh.neotime.timeedit.MapFloor
import com.melluh.neotime.timeedit.MapOverrides
import com.melluh.neotime.timeedit.PoiOverride
import com.melluh.neotime.timeedit.Room
import com.melluh.neotime.timeedit.RoomLocation
import com.melluh.neotime.timeedit.StairLinks
import com.melluh.neotime.timeedit.TIMEEDIT_ZONE
import com.melluh.neotime.ui.AccountChip
import com.melluh.neotime.ui.rooms.AvailabilityBar
import com.melluh.neotime.ui.rooms.BAR_HEIGHT
import com.melluh.neotime.ui.rooms.TimeAxis
import com.melluh.neotime.ui.rooms.freeNowColor
import com.melluh.neotime.ui.rooms.roomSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** A room to point out. Not a data class: asking for the same room again should move there again. */
class MapFocus(val poiId: Long)

/** [features] belong to [floor]. [bookable] holds project rooms by their MazeMap identifiers. */
data class MapUiState(
    val loading: Boolean = true,
    val buildings: List<MapBuilding> = emptyList(),
    val building: MapBuilding? = null,
    val floor: MapFloor? = null,
    val features: List<MapFeature> = emptyList(),
    val stale: Boolean = false,
    val error: String? = null,
    val focus: MapFocus? = null,
    val bookable: Map<String, Room> = emptyMap(),
    val stairLinks: Map<Long, StairLinks> = emptyMap(),
)

/** [result] is how [room]'s day looks as of [now]. */
data class RoomDay(val room: Room, val now: LocalDateTime, val result: RoomsResult)

class MapViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state

    private val watched = MutableStateFlow<Room?>(null)
    private val _roomDay = MutableStateFlow<RoomDay?>(null)
    val roomDay: StateFlow<RoomDay?> = _roomDay

    /** What the user picked here; null means restore the last session's pick. */
    private val picked = MutableStateFlow<MapSelection?>(null)
    private val reload = MutableStateFlow(0)
    private var buildingsStale = false

    init {
        viewModelScope.launch {
            // collectLatest drops a floor that's still loading when another one gets picked
            combine(picked, reload) { selection, _ -> selection }.collectLatest { load(it) }
        }
        viewModelScope.launch {
            container.focus.onMap.filterNotNull().collect {
                focus(it)
                container.focus.clearMap()
            }
        }
        viewModelScope.launch {
            // Room lists need a login, so a login can change what's bookable
            container.session.state
                .map { it !is SessionState.LoggedOut && it !is SessionState.NeedsInteractiveLogin }
                .distinctUntilChanged()
                .collectLatest {
                    val bookable = container.rooms.bookableByIdentifier()
                    _state.update { it.copy(bookable = bookable) }
                }
        }
        viewModelScope.launch {
            watched.collectLatest { room ->
                // What's free depends on the clock, so a room left open has to keep up with it
                while (room != null) {
                    val now = LocalDateTime.now(TIMEEDIT_ZONE)
                    _roomDay.value = RoomDay(room, now, container.rooms.availability(room, now.toLocalDate(), now))
                    delay(ROOM_REFRESH_MS)
                }
            }
        }
    }

    private fun focus(location: RoomLocation) {
        _state.update { it.copy(focus = MapFocus(location.poiId)) }
        picked.value = MapSelection(location.buildingId, location.floorId)
    }

    private suspend fun load(selection: MapSelection?) {
        _state.update { it.copy(loading = true, error = null) }
        val buildings = _state.value.buildings.ifEmpty {
            when (val result = container.maps.buildings()) {
                is MapResult.Ok -> {
                    buildingsStale = result.stale
                    result.value
                }
                is MapResult.Failed -> {
                    _state.update { it.copy(loading = false, error = result.message) }
                    return
                }
            }
        }
        val wanted = selection ?: container.maps.lastSelection.first()
        val building = buildings.firstOrNull { it.id == wanted.buildingId }
        // A building asked for by name must not quietly become another one; a remembered one can
        if (building == null && selection != null) {
            _state.update { it.copy(loading = false, buildings = buildings, focus = null, error = "This building isn't on the map.") }
            return
        }
        val shown = building ?: buildings.firstOrNull()
        val floor = shown?.floors?.firstOrNull { it.id == wanted.floorId } ?: shown?.defaultFloor
        _state.update {
            it.copy(
                buildings = buildings,
                building = shown,
                floor = floor,
                features = if (it.floor?.id == floor?.id) it.features else emptyList(),
                stairLinks = if (it.floor?.id == floor?.id) it.stairLinks else emptyMap(),
                stale = buildingsStale,
            )
        }
        if (floor == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        if (selection != null && shown != null) container.maps.select(shown.id, floor.id)
        when (val result = container.maps.floorFeatures(floor.id)) {
            is MapResult.Ok -> {
                _state.update { it.copy(loading = false, features = result.value, stale = buildingsStale || result.stale) }
                // After the floor's up, since this can mean fetching the ones around it
                if (shown != null) {
                    val links = container.maps.stairLinks(shown, floor, result.value)
                    _state.update { it.copy(stairLinks = links) }
                }
            }
            is MapResult.Failed -> _state.update { it.copy(loading = false, error = result.message) }
        }
    }

    fun selectBuilding(building: MapBuilding) {
        _state.update { it.copy(focus = null) }
        picked.value = MapSelection(building.id, building.defaultFloor?.id)
    }

    fun selectFloor(floor: MapFloor) {
        _state.update { it.copy(focus = null) }
        picked.value = MapSelection(_state.value.building?.id, floor.id)
    }

    fun retry() {
        reload.value++
    }

    val canEdit: Boolean get() = container.maps.canEditOverrides

    fun addEntrance(at: GeoPoint) {
        val building = _state.value.building ?: return
        val floor = _state.value.floor ?: return
        edit { it.withEntrance(building.id, MapEntrance(floor.id, at)) }
    }

    fun replaceEntrance(old: MapEntrance, new: MapEntrance) {
        val building = _state.value.building ?: return
        edit { it.withoutEntrance(building.id, old).withEntrance(building.id, new) }
    }

    fun removeEntrance(entrance: MapEntrance) {
        val building = _state.value.building ?: return
        edit { it.withoutEntrance(building.id, entrance) }
    }

    /** A null [type] goes back to MazeMap's. */
    fun setRoomType(feature: MapFeature, type: RoomType?) {
        val override = type?.let { PoiOverride(note = feature.identifier ?: feature.title, kind = it.kind, typeNames = it.typeNames) }
        edit { it.withPoi(feature.id, override) }
    }

    private fun edit(change: (MapOverrides) -> MapOverrides) {
        viewModelScope.launch {
            container.maps.editOverrides(change)
            // A kept focus would pull the view back to that room as soon as the floor redraws
            _state.update { it.copy(buildings = emptyList(), focus = null) }
            reload.value++
        }
    }

    /** Keeps [roomDay] current for [room], until this is called with another room or null. */
    fun watchRoom(room: Room?) {
        watched.value = room
    }

    private companion object {
        const val ROOM_REFRESH_MS = 60_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(container: AppContainer, onOpenSettings: () -> Unit, onOpenRooms: () -> Unit) {
    val vm = viewModel { MapViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val roomDay by vm.roomDay.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()

    // Up here because the canvas goes away while a floor loads, and the view has to outlast that
    val viewport = remember(state.building?.id) { MapViewport() }
    val bookable = remember(state.features, state.bookable) {
        state.features.filter { it.identifier in state.bookable }.map { it.id }.toSet()
    }
    var tapped by remember { mutableStateOf<MapFeature?>(null) }
    var pickingBuilding by remember { mutableStateOf(false) }
    val recentBuildings by container.maps.recentBuildings.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf(false) }
    var editingRoom by remember { mutableStateOf<MapFeature?>(null) }
    var editingEntrance by remember { mutableStateOf<MapEntrance?>(null) }
    val haptics = LocalHapticFeedback.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { BuildingTitle(state.building, state.buildings.isNotEmpty()) { pickingBuilding = true } },
                actions = {
                    if (vm.canEdit) {
                        FilledIconToggleButton(checked = editing, onCheckedChange = { editing = it }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit map")
                        }
                    }
                    AccountChip(session, onClick = onOpenSettings)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Keeps its space when idle, so the map doesn't jump on every floor change
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.stale) {
                Text(
                    "May be out of date",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val building = state.building
                val floor = state.floor
                when {
                    state.error != null -> Notice(state.error.orEmpty(), onRetry = vm::retry)
                    building != null && floor != null && state.features.isNotEmpty() -> {
                        FloorCanvas(
                            building,
                            floor,
                            state.features,
                            viewport,
                            bookable = bookable,
                            stairLinks = state.stairLinks,
                            focused = state.focus?.poiId,
                            focusKey = state.focus,
                            onTap = { if (editing) editingRoom = it else tapped = it },
                            modifier = Modifier.fillMaxSize(),
                            editing = if (editing) {
                                MapEditGestures(
                                    onTapEntrance = { editingEntrance = it },
                                    onLongPress = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.addEntrance(it)
                                    },
                                )
                            } else {
                                null
                            },
                        )
                        NorthArrow(building.bearing, Modifier.align(Alignment.TopEnd).padding(8.dp))
                        if (editing) {
                            Text(
                                "Tap a room to change its type. Long-press to add an entrance on this floor.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp, end = 52.dp),
                            )
                        }
                    }
                    !state.loading -> Notice("Nothing is mapped here.")
                }
                Text(
                    "Map data © MazeMap",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
                state.building?.let {
                    FloorPicker(
                        it.floors,
                        state.floor,
                        vm::selectFloor,
                        Modifier.align(Alignment.CenterEnd).padding(8.dp),
                    )
                }
            }
        }
    }

    if (pickingBuilding) {
        BuildingSheet(
            state.buildings,
            recentBuildings,
            onSelect = {
                pickingBuilding = false
                vm.selectBuilding(it)
            },
            onDismiss = { pickingBuilding = false },
        )
    }

    val tappedRoom = tapped?.let { state.bookable[it.identifier] }
    LifecycleResumeEffect(vm, tappedRoom) {
        vm.watchRoom(tappedRoom)
        onPauseOrDispose { vm.watchRoom(null) }
    }

    tapped?.let { feature ->
        RoomSheet(
            feature,
            room = tappedRoom,
            // Can briefly still be the room that was open before this one
            day = roomDay?.takeIf { it.room == tappedRoom },
            onViewInRooms = { room ->
                tapped = null
                container.focus.showInRooms(room)
                onOpenRooms()
            },
            onDismiss = { tapped = null },
        )
    }

    editingRoom?.let { feature ->
        RoomTypeSheet(
            feature,
            onPick = {
                editingRoom = null
                vm.setRoomType(feature, it)
            },
            onDismiss = { editingRoom = null },
        )
    }

    editingEntrance?.let { entrance ->
        // Stays open with each change, since one entrance usually gets both of these
        fun replaceWith(new: MapEntrance) {
            editingEntrance = new
            vm.replaceEntrance(entrance, new)
        }
        EntranceSheet(
            entrance,
            bearing = state.building?.bearing ?: 0.0,
            onSetMain = { replaceWith(entrance.copy(main = it)) },
            onSetDirection = { replaceWith(entrance.copy(direction = it)) },
            onRemove = {
                editingEntrance = null
                vm.removeEntrance(entrance)
            },
            onDismiss = { editingEntrance = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomSheet(feature: MapFeature, room: Room?, day: RoomDay?, onViewInRooms: (Room) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(room?.name ?: feature.label.orEmpty(), style = MaterialTheme.typography.titleLarge)
            if (room != null) {
                Text(
                    room.capacity?.let { if (it == 1) "Bookable · 1 person" else "Bookable · $it people" } ?: "Bookable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RoomToday(day)
                Button(onClick = { onViewInRooms(room) }) { Text("View in Rooms") }
            }
        }
    }
}

/** How the room's day looks. Null [day] means it's still loading. */
@Composable
private fun RoomToday(day: RoomDay?) {
    val result = day?.result
    val status = (result as? RoomsResult.Ok)?.rooms?.firstOrNull()
    val summary = status?.let { roomSummary(it, day.now.toLocalTime()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            when {
                summary != null -> summary.text
                result is RoomsResult.Failed -> result.message
                result != null -> "Log in to see availability"
                else -> "Checking availability…"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (summary?.freeNow == true) freeNowColor() else MaterialTheme.colorScheme.onSurface,
        )
        if (status != null || result == null) TimeAxis()
        when {
            status != null -> AvailabilityBar(status, window = null, past = null, now = day.now.toLocalTime())
            // Keeps the sheet from jumping when the bar arrives
            result == null -> Spacer(Modifier.height(BAR_HEIGHT))
        }
    }
}

/** Opens the building picker. Says "Map" until there's a building to name. */
@Composable
private fun BuildingTitle(selected: MapBuilding?, canPick: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable(enabled = canPick, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(
            selected?.name ?: "Map",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (canPick) Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Change building")
    }
}

/** Top floor first, the way a lift panel reads. */
@Composable
private fun FloorPicker(
    floors: List<MapFloor>,
    selected: MapFloor?,
    onSelect: (MapFloor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    // A room opened from another tab can be on a floor further down the column
    LaunchedEffect(selected?.id) {
        val index = floors.asReversed().indexOfFirst { it.id == selected?.id }
        if (index >= 0) list.animateScrollToItem(index)
    }
    // Unselected chips are see-through, and the map runs underneath
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(floors.asReversed(), key = { it.id }) { floor ->
                FilterChip(
                    selected = floor.id == selected?.id,
                    onClick = { onSelect(floor) },
                    label = { Text(floor.name) },
                )
            }
        }
    }
}

@Composable
private fun Notice(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center)
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}
