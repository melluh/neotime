package com.melluh.neotime.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.melluh.neotime.timeedit.MapBuilding
import java.text.Normalizer

/** [recent] is latest first; a building is in only one of the two. */
data class BuildingList(val recent: List<MapBuilding>, val others: List<MapBuilding>)

fun buildingList(buildings: List<MapBuilding>, recentIds: List<Long>, query: String): BuildingList {
    val wanted = query.trim().folded()
    val matches = buildings.filter { wanted in it.name.folded() }
    val recent = recentIds.mapNotNull { id -> matches.firstOrNull { it.id == id } }
    return BuildingList(recent, matches.filter { it !in recent })
}

/** So "carre" finds Carré. */
private fun String.folded() = Normalizer.normalize(this, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

private val MARKS = Regex("\\p{M}")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildingSheet(
    buildings: List<MapBuilding>,
    recentIds: List<Long>,
    onSelect: (MapBuilding) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val list = remember(buildings, recentIds, query) { buildingList(buildings, recentIds, query) }

    // Fully open from the start: half a sheet would end up behind the keyboard
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight().imePadding()) {
            val search = remember { FocusRequester() }
            LaunchedEffect(Unit) { search.requestFocus() }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search buildings") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(search),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (list.recent.isNotEmpty()) section("Recent", list.recent, onSelect)
                section(if (list.recent.isNotEmpty()) "Other buildings" else null, list.others, onSelect)
                if (list.recent.isEmpty() && list.others.isEmpty()) {
                    item(key = "nothing") {
                        Text(
                            "No buildings found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(title: String?, buildings: List<MapBuilding>, onSelect: (MapBuilding) -> Unit) {
    if (buildings.isEmpty()) return
    if (title != null) {
        item(key = title) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
    }
    items(buildings, key = { it.id }) { building ->
        ListItem(
            headlineContent = { Text(building.name) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onSelect(building) },
        )
    }
}
