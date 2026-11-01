package com.melluh.neotime.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.melluh.neotime.timeedit.GeoPoint
import com.melluh.neotime.timeedit.MapBuilding
import com.melluh.neotime.timeedit.MapFeature
import com.melluh.neotime.timeedit.MapFloor
import com.melluh.neotime.timeedit.Rings
import com.melluh.neotime.timeedit.withoutBridges
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

private const val METRES_PER_DEGREE_LON = 111_320.0
private const val METRES_PER_DEGREE_LAT = 110_540.0
private const val MAX_ZOOM = 16f
private const val MARGIN = 0.94f

/** Declared in drawing order; hallways and areas overlap the rooms on top of them. */
private enum class Layer { AREA, HALLWAY, ROOM, LECTURE_HALL, TOILET, STAIRS_AND_LIFTS }

private fun layerOf(feature: MapFeature): Layer = when {
    feature.kind == "stairs" || feature.kind == "elevator" || "Elevators" in feature.typeNames -> Layer.STAIRS_AND_LIFTS
    feature.typeNames.any { it.startsWith("Toilet") } -> Layer.TOILET
    "Lecture Hall" in feature.typeNames -> Layer.LECTURE_HALL
    feature.kind == "circulation_room" || "Hallway" in feature.typeNames -> Layer.HALLWAY
    feature.kind == "generic" || feature.kind == "zone" -> Layer.AREA
    else -> Layer.ROOM
}

/** [side] is the side of a square of the same area, in metres: how much room a label has. */
private class Shape(val layer: Layer, val path: Path, val side: Float, val label: String?, val labelAt: Offset?)

/** Where the map is looking. Every floor of a building uses the same one, so floors line up. */
class MapViewport {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
}

/**
 * A whole building's footprint in metres from its top-left corner, with y pointing down. Floors
 * are all placed in this, not in their own bounds, so that a spot on the screen stays the same
 * spot in the building from floor to floor.
 */
private class BuildingFrame private constructor(
    private val minLon: Double,
    private val maxLat: Double,
    private val lonScale: Double,
    val width: Float,
    val height: Float,
) {
    // Relative to the building while still in Double: a Float can't hold 52.24° to better than
    // about 40 cm, which shows as jagged walls when zoomed in.
    fun project(p: GeoPoint) = Offset(
        ((p.lon - minLon) * lonScale).toFloat(),
        ((maxLat - p.lat) * METRES_PER_DEGREE_LAT).toFloat(),
    )

    companion object {
        fun of(points: List<GeoPoint>): BuildingFrame? {
            if (points.isEmpty()) return null
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val lonScale = cos(Math.toRadians(maxLat)) * METRES_PER_DEGREE_LON
            return BuildingFrame(
                minLon,
                maxLat,
                lonScale,
                ((maxLon - minLon) * lonScale).toFloat(),
                ((maxLat - minLat) * METRES_PER_DEGREE_LAT).toFloat(),
            )
        }
    }
}

private class FloorGeometry(val outline: Path?, val shapes: List<Shape>) {
    companion object {
        fun of(frame: BuildingFrame, floor: MapFloor, features: List<MapFeature>): FloorGeometry {
            fun path(rings: Rings) = Path().apply {
                fillType = PathFillType.EvenOdd
                rings.filter { it.isNotEmpty() }.forEach { ring ->
                    val start = frame.project(ring.first())
                    moveTo(start.x, start.y)
                    ring.drop(1).map(frame::project).forEach { lineTo(it.x, it.y) }
                    close()
                }
            }

            fun area(ring: List<GeoPoint>): Float {
                val projected = ring.map(frame::project)
                val twice = projected.indices.sumOf { i ->
                    val a = projected[i]
                    val b = projected[(i + 1) % projected.size]
                    (a.x * b.y - b.x * a.y).toDouble()
                }
                return abs(twice / 2).toFloat()
            }

            val shapes = features.mapNotNull { feature ->
                val rings = feature.rings?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val area = area(rings.first())
                area to Shape(
                    layer = layerOf(feature),
                    path = path(rings),
                    side = sqrt(area),
                    label = feature.label,
                    labelAt = feature.labelPoint?.let(frame::project),
                )
            }
                // Within a layer, biggest first, so that nothing hides what's inside it
                .sortedWith(compareBy({ it.second.layer }, { -it.first }))
                .map { it.second }

            return FloorGeometry(
                outline = floor.outline?.let { path(it.withoutBridges()) },
                shapes = shapes,
            )
        }
    }
}

/** A floor plan you can pan and pinch. */
@Composable
fun FloorCanvas(
    building: MapBuilding,
    floor: MapFloor,
    features: List<MapFeature>,
    viewport: MapViewport,
    modifier: Modifier = Modifier,
) {
    val frame = remember(building.id, features) {
        val outlines = building.floors.flatMap { it.outline.orEmpty().flatten() }
        BuildingFrame.of(outlines.ifEmpty { features.flatMap { it.rings.orEmpty().flatten() } })
    } ?: return
    val geometry = remember(frame, floor.id, features) { FloorGeometry.of(frame, floor, features) }

    val colors = MaterialTheme.colorScheme
    val fills = remember(colors) {
        mapOf(
            Layer.AREA to colors.surfaceContainer,
            Layer.HALLWAY to colors.surfaceContainerHighest,
            Layer.ROOM to colors.surface,
            Layer.LECTURE_HALL to colors.primaryContainer,
            Layer.TOILET to colors.tertiaryContainer,
            Layer.STAIRS_AND_LIFTS to colors.secondaryContainer,
        )
    }
    val wall = colors.outline
    val floorFill = colors.surfaceVariant

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurface)
    // Filled in as labels come into view, since most floors never get zoomed all the way
    val labels = remember(features, labelStyle) { HashMap<String, TextLayoutResult>() }

    Canvas(
        modifier
            .clipToBounds()
            .pointerInput(viewport) {
                detectTransformGestures { centroid, delta, gestureZoom, _ ->
                    val zoom = viewport.zoom
                    val pan = viewport.pan
                    val updated = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                    // Keeps the spot between the fingers in place
                    val fromCenter = centroid - Offset(size.width / 2f, size.height / 2f)
                    val moved = fromCenter - (fromCenter - pan) * (updated / zoom) + delta
                    val reachX = size.width * updated / 2f
                    val reachY = size.height * updated / 2f
                    viewport.pan = Offset(moved.x.coerceIn(-reachX, reachX), moved.y.coerceIn(-reachY, reachY))
                    viewport.zoom = updated
                }
            },
    ) {
        if (size.isEmpty() || frame.width <= 0f || frame.height <= 0f) return@Canvas
        val scale = fitScale(frame, size) * viewport.zoom
        val originX = center.x + viewport.pan.x - frame.width / 2 * scale
        val originY = center.y + viewport.pan.y - frame.height / 2 * scale

        withTransform({
            translate(originX, originY)
            scale(scale, scale, Offset.Zero)
        }) {
            // Strokes go through the scale too, so they're undone to stay thin at any zoom
            val line = Stroke(width = 1.dp.toPx() / scale)
            geometry.outline?.let { drawPath(it, floorFill) }
            geometry.shapes.forEach { shape ->
                drawPath(shape.path, fills.getValue(shape.layer))
                drawPath(shape.path, wall, style = line)
            }
            geometry.outline?.let { drawPath(it, wall, style = line) }
        }

        drawLabels(geometry, scale, Offset(originX, originY)) { text ->
            labels.getOrPut(text) { measurer.measure(text, labelStyle) }
        }
    }
}

private fun fitScale(frame: BuildingFrame, size: Size): Float =
    min(size.width / frame.width, size.height / frame.height) * MARGIN

/** Outside the map's transform, so that text keeps its size; shown once it fits its room. */
private fun DrawScope.drawLabels(geometry: FloorGeometry, scale: Float, origin: Offset, measure: (String) -> TextLayoutResult) {
    geometry.shapes.forEach { shape ->
        val text = shape.label ?: return@forEach
        val at = shape.labelAt ?: return@forEach
        val room = shape.side * scale
        if (room < MIN_LABEL_ROOM_DP * density) return@forEach
        val x = origin.x + at.x * scale
        val y = origin.y + at.y * scale
        if (x !in -room..size.width + room || y !in -room..size.height + room) return@forEach
        val layout = measure(text)
        if (layout.size.width > room) return@forEach
        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
    }
}

private const val MIN_LABEL_ROOM_DP = 16
