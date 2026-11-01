package com.melluh.neotime.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melluh.neotime.AppContainer
import com.melluh.neotime.data.MapResult
import com.melluh.neotime.data.MapSelection
import com.melluh.neotime.timeedit.MapBuilding
import com.melluh.neotime.timeedit.MapFeature
import com.melluh.neotime.timeedit.MapFloor
import com.melluh.neotime.ui.AccountChip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [features] belong to [floor]. */
data class MapUiState(
    val loading: Boolean = true,
    val buildings: List<MapBuilding> = emptyList(),
    val building: MapBuilding? = null,
    val floor: MapFloor? = null,
    val features: List<MapFeature> = emptyList(),
    val stale: Boolean = false,
    val error: String? = null,
)

class MapViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state

    /** What the user picked here; null means restore the last session's pick. */
    private val picked = MutableStateFlow<MapSelection?>(null)
    private val reload = MutableStateFlow(0)
    private var buildingsStale = false

    init {
        viewModelScope.launch {
            // collectLatest drops a floor that's still loading when another one gets picked
            combine(picked, reload) { selection, _ -> selection }.collectLatest { load(it) }
        }
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
        val building = buildings.firstOrNull { it.id == wanted.buildingId } ?: buildings.firstOrNull()
        val floor = building?.floors?.firstOrNull { it.id == wanted.floorId } ?: building?.defaultFloor
        _state.update {
            it.copy(
                buildings = buildings,
                building = building,
                floor = floor,
                features = if (it.floor?.id == floor?.id) it.features else emptyList(),
                stale = buildingsStale,
            )
        }
        if (floor == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        if (selection != null && building != null) container.maps.select(building.id, floor.id)
        when (val result = container.maps.floorFeatures(floor.id)) {
            is MapResult.Ok -> _state.update {
                it.copy(loading = false, features = result.value, stale = buildingsStale || result.stale)
            }
            is MapResult.Failed -> _state.update { it.copy(loading = false, error = result.message) }
        }
    }

    fun selectBuilding(building: MapBuilding) {
        picked.value = MapSelection(building.id, building.defaultFloor?.id)
    }

    fun selectFloor(floor: MapFloor) {
        picked.value = MapSelection(_state.value.building?.id, floor.id)
    }

    fun retry() {
        reload.value++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(container: AppContainer, onOpenSettings: () -> Unit) {
    val vm = viewModel { MapViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()

    // Up here because the canvas goes away while a floor loads, and the view has to outlast that
    val viewport = remember(state.building?.id) { MapViewport() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map") },
                actions = { AccountChip(session, onClick = onOpenSettings) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Keeps its space when idle, so the pickers don't jump on every floor change
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.buildings.isNotEmpty()) {
                BuildingPicker(state.buildings, state.building, vm::selectBuilding)
            }
            state.building?.let { FloorPicker(it.floors, state.floor, vm::selectFloor) }
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
                    building != null && floor != null && state.features.isNotEmpty() ->
                        FloorCanvas(building, floor, state.features, viewport, Modifier.fillMaxSize())
                    !state.loading -> Notice("Nothing is mapped here.")
                }
                Text(
                    "Map data © MazeMap",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun BuildingPicker(buildings: List<MapBuilding>, selected: MapBuilding?, onSelect: (MapBuilding) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedButton(onClick = { open = true }) {
            Text(selected?.name ?: "Building")
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            buildings.forEach { building ->
                DropdownMenuItem(
                    text = { Text(building.name) },
                    onClick = {
                        open = false
                        onSelect(building)
                    },
                )
            }
        }
    }
}

/** Top floor first, the way a lift panel reads. */
@Composable
private fun FloorPicker(floors: List<MapFloor>, selected: MapFloor?, onSelect: (MapFloor) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
