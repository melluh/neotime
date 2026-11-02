package com.melluh.neotime.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.melluh.neotime.timeedit.MapEntrance
import com.melluh.neotime.timeedit.MapFeature
import kotlin.math.abs

/** What a room can be turned into, in the [MapFeature.kind] and types that make it draw that way. */
enum class RoomType(val label: String, val kind: String, val typeNames: List<String>) {
    ROOM("Room", "room", emptyList()),
    LECTURE_HALL("Lecture hall", "room", listOf("Lecture Hall")),
    HALLWAY("Hallway", "room", listOf("Hallway")),
    TOILET("Toilet", "room", listOf("Toilet")),
    STAIRS("Stairs", "stairs", listOf("Stairs")),
    LIFT("Lift", "elevator", listOf("Elevators")),
    TECHNICAL("Technical room", "room", listOf("Technical Room")),
    ;

    companion object {
        fun of(feature: MapFeature): RoomType? =
            entries.firstOrNull { it.kind == feature.kind && it.typeNames == feature.typeNames }
    }
}

/** A null type goes back to whatever MazeMap says this room is. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoomTypeSheet(feature: MapFeature, onPick: (RoomType?) -> Unit, onDismiss: () -> Unit) {
    val current = RoomType.of(feature)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(feature.title ?: "Untitled", style = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(feature.identifier, "#${feature.id}", feature.kind).joinToString(" · ") +
                    feature.typeNames.joinToString(prefix = " · ").takeIf { feature.typeNames.isNotEmpty() }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoomType.entries.forEach { type ->
                    FilterChip(selected = type == current, onClick = { onPick(type) }, label = { Text(type.label) })
                }
            }
            OutlinedButton(onClick = { onPick(null) }) { Text("Use MazeMap's type") }
        }
    }
}

/** [bearing] is the building's, so that the arrows point the way they do on the map behind this. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntranceSheet(
    entrance: MapEntrance,
    bearing: Double,
    onSetMain: (Boolean) -> Unit,
    onSetDirection: (Double?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Entrance", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Main entrance")
                Switch(checked = entrance.main, onCheckedChange = onSetMain)
            }
            Text("Which way you walk in")
            DirectionDial(entrance.directionOnMap(bearing)) { onMap ->
                onSetDirection(onMap?.let { (bearing + it).mod(360.0) })
            }
            OutlinedButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

/** Clockwise from up, by row; the middle one is for no direction. */
private val DIAL = listOf(listOf(315, 0, 45), listOf(270, null, 90), listOf(225, 180, 135))

/** [selected] and what [onPick] gets are clockwise from straight up on the map. */
@Composable
private fun DirectionDial(selected: Double?, onPick: (Double?) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        DIAL.forEach { row ->
            Row {
                row.forEach { angle ->
                    FilledIconToggleButton(
                        checked = if (angle == null) selected == null else selected != null && isSameAngle(selected, angle),
                        onCheckedChange = { onPick(angle?.toDouble()) },
                    ) {
                        if (angle == null) {
                            Icon(Icons.Filled.Close, contentDescription = "No direction")
                        } else {
                            // This arrow's drawn pointing right
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "$angle degrees",
                                modifier = Modifier.rotate(angle - 90f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isSameAngle(a: Double, b: Int) = abs((a - b + 180).mod(360.0) - 180) < 1
