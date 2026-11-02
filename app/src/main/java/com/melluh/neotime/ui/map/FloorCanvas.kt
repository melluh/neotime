package com.melluh.neotime.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.melluh.neotime.R
import com.melluh.neotime.timeedit.GeoPoint
import com.melluh.neotime.timeedit.MapBuilding
import com.melluh.neotime.timeedit.MapEntrance
import com.melluh.neotime.timeedit.MapFeature
import com.melluh.neotime.timeedit.MapFloor
import com.melluh.neotime.timeedit.MapProjection
import com.melluh.neotime.timeedit.Rings
import com.melluh.neotime.timeedit.StairLinks
import com.melluh.neotime.timeedit.WallLine
import com.melluh.neotime.timeedit.contains
import com.melluh.neotime.timeedit.joinShapes
import com.melluh.neotime.timeedit.withoutBridges
import com.melluh.neotime.ui.rooms.freeNowColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val MAX_ZOOM = 16f
private const val MARGIN = 0.94f
private const val FOCUS_ZOOM = 5f

/** Marks bookable rooms over whatever their layer draws them in. */
private const val BOOKABLE_ALPHA = 0.25f
private const val FREE_ALPHA = 0.45f
private const val FOCUS_ALPHA = 0.6f

/** Each side of a gap between hallways gets this, so together they cover the widest gap that's joined. */
private const val SEAM_WIDTH_METRES = 0.45f

private const val ENTRANCE_DP = 22
private const val ENTRANCE_ICON_SCALE = 0.7f

/** How far from a marker a tap still counts as being on it. */
private const val ENTRANCE_REACH_DP = 24

/** Declared in drawing order; hallways and areas overlap the rooms on top of them. */
private enum class Layer { AREA, HALLWAY, ROOM, LECTURE_HALL, TOILET, LIFT, STAIRS }

private fun layerOf(feature: MapFeature): Layer = when {
    feature.kind == "elevator" || "Elevators" in feature.typeNames -> Layer.LIFT
    feature.isStairs -> Layer.STAIRS
    feature.typeNames.any { it.startsWith("Toilet") } -> Layer.TOILET
    "Lecture Hall" in feature.typeNames -> Layer.LECTURE_HALL
    feature.kind == "circulation_room" || "Hallway" in feature.typeNames -> Layer.HALLWAY
    feature.kind == "generic" || feature.kind == "zone" -> Layer.AREA
    else -> Layer.ROOM
}

private class Chip(val background: Color, val text: Color)

/** [side] is the side of a square of the same area, in metres: how much room a label has. */
private class Shape(val feature: MapFeature, val layer: Layer, val path: Path, val side: Float, val labelAt: Offset?) {
    val label: String? get() = feature.label
}

/** Where the map is looking. Every floor of a building uses the same one, so floors line up. */
class MapViewport {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    /** Moves to [zoom], with [pan] kept to where the map can reach in a view of [size]. */
    fun move(zoom: Float, pan: Offset, size: Size) {
        val reachX = size.width * zoom / 2f
        val reachY = size.height * zoom / 2f
        this.pan = Offset(pan.x.coerceIn(-reachX, reachX), pan.y.coerceIn(-reachY, reachY))
        this.zoom = zoom
    }
}

/**
 * A whole building's footprint in metres from its top-left corner as drawn, facing the building's
 * bearing. Floors are all placed in this, not in their own bounds, so that a spot on the screen
 * stays the same spot in the building from floor to floor.
 */
private class BuildingFrame private constructor(private val projection: MapProjection) {
    val width = projection.width.toFloat()
    val height = projection.height.toFloat()

    fun project(p: GeoPoint) = Offset(projection.x(p).toFloat(), projection.y(p).toFloat())

    fun unproject(at: Offset) = projection.unproject(at.x.toDouble(), at.y.toDouble())

    companion object {
        fun of(points: List<GeoPoint>, bearing: Double): BuildingFrame? =
            if (points.isEmpty()) null else BuildingFrame(MapProjection(points, bearing))
    }
}

private class FloorGeometry(
    val outline: Path?,
    val shapes: List<Shape>,
    val hallwayWalls: Path,
    val hallwaySeams: Path,
) {
    val belowHallways = shapes.filter { it.layer < Layer.HALLWAY }
    val hallways = shapes.filter { it.layer == Layer.HALLWAY }
    val aboveHallways = shapes.filter { it.layer > Layer.HALLWAY }

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
                    feature = feature,
                    layer = layerOf(feature),
                    path = path(rings),
                    side = sqrt(area),
                    labelAt = feature.labelPoint?.let(frame::project),
                )
            }
                // Within a layer, biggest first, so that nothing hides what's inside it
                .sortedWith(compareBy({ it.second.layer }, { -it.first }))
                .map { it.second }

            fun lines(lines: List<WallLine>) = Path().apply {
                lines.forEach { (from, to) ->
                    val start = frame.project(from)
                    val end = frame.project(to)
                    moveTo(start.x, start.y)
                    lineTo(end.x, end.y)
                }
            }

            val joined = joinShapes(shapes.filter { it.layer == Layer.HALLWAY }.map { it.feature.rings.orEmpty() })
            return FloorGeometry(
                outline = floor.outline?.let { path(it.withoutBridges()) },
                shapes = shapes,
                hallwayWalls = lines(joined.walls),
                hallwaySeams = lines(joined.seams),
            )
        }
    }
}

/** What [FloorCanvas] does differently while the map's being edited. */
class MapEditGestures(val onTapEntrance: (MapEntrance) -> Unit, val onLongPress: (GeoPoint) -> Unit)

/**
 * A floor plan you can pan, pinch and tap. [bookable] marks features by id, with those in [free]
 * marked as free now. The view moves to [focused] whenever that changes, and [focusKey] can change
 * to move there again. With [editing], every room can be tapped instead of only the ones you'd go to.
 */
@Composable
fun FloorCanvas(
    building: MapBuilding,
    floor: MapFloor,
    features: List<MapFeature>,
    viewport: MapViewport,
    bookable: Set<Long>,
    stairLinks: Map<Long, StairLinks>,
    free: Set<Long>,
    focused: Long?,
    focusKey: Any?,
    onTap: (MapFeature) -> Unit,
    modifier: Modifier = Modifier,
    editing: MapEditGestures? = null,
) {
    val frame = remember(building.id, features) {
        val outlines = building.floors.flatMap { it.outline.orEmpty().flatten() }
        BuildingFrame.of(outlines.ifEmpty { features.flatMap { it.rings.orEmpty().flatten() } }, building.bearing)
    } ?: return
    val geometry = remember(frame, floor.id, features) { FloorGeometry.of(frame, floor, features) }
    val entrances = remember(frame, floor.id, building.entrances) {
        building.entrances.filter { it.floorId == floor.id }.map { it to frame.project(it.at) }
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentEditing by rememberUpdatedState(editing)
    val currentEntrances by rememberUpdatedState(entrances)

    LaunchedEffect(focusKey, geometry, viewport, canvasSize) {
        val shape = geometry.shapes.firstOrNull { it.feature.id == focused } ?: return@LaunchedEffect
        val size = canvasSize.toSize()
        val at = shape.labelAt ?: shape.feature.rings?.firstOrNull()?.firstOrNull()?.let(frame::project)
        if (at == null || size.isEmpty() || frame.width <= 0f || frame.height <= 0f) return@LaunchedEffect
        val zoom = max(viewport.zoom, FOCUS_ZOOM)
        val scale = fitScale(frame, size) * zoom
        viewport.move(zoom, Offset(frame.width / 2 - at.x, frame.height / 2 - at.y) * scale, size)
    }

    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val fills = remember(colors, dark) {
        mapOf(
            Layer.AREA to colors.surfaceContainer,
            Layer.HALLWAY to colors.surfaceContainerLowest,
            Layer.ROOM to colors.surfaceContainerHighest,
            Layer.LECTURE_HALL to colors.primaryContainer,
            Layer.TOILET to if (dark) TOILET_DARK else TOILET_LIGHT,
            // Green whatever the theme: the way up or down is what you scan a floor for
            Layer.LIFT to if (dark) VERTICAL_DARK else VERTICAL_LIGHT,
            Layer.STAIRS to if (dark) VERTICAL_DARK else VERTICAL_LIGHT,
        )
    }
    val wall = colors.outline
    val accent = colors.primary
    val icons = MapIcons(
        stairs = painterResource(R.drawable.ic_map_stairs),
        lift = painterResource(R.drawable.ic_map_lift),
        up = painterResource(R.drawable.ic_map_up),
        down = painterResource(R.drawable.ic_map_down),
        upDown = painterResource(R.drawable.ic_map_up_down),
        info = painterResource(R.drawable.ic_map_info),
        toilet = painterResource(R.drawable.ic_map_toilet),
        toiletMen = painterResource(R.drawable.ic_map_toilet_men),
        toiletWomen = painterResource(R.drawable.ic_map_toilet_women),
        toiletAccessible = painterResource(R.drawable.ic_map_toilet_accessible),
    )
    val iconTint = colors.onSurface
    val entranceIcon = rememberVectorPainter(Icons.AutoMirrored.Filled.ExitToApp)
    val directionIcon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowForward)
    val onAccent = colors.onPrimary
    val freeFill = freeNowColor()
    val bookableChip = Chip(colors.primary, colors.onPrimary)
    val freeChip = Chip(freeFill, if (freeFill.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White)

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurface)
    // Filled in as labels come into view, since most floors never get zoomed all the way
    val labels = remember(features, labelStyle) { HashMap<String, TextLayoutResult>() }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { canvasSize = it }
            .pointerInput(viewport, frame, geometry) {
                fun scale() = fitScale(frame, size.toSize()) * viewport.zoom

                fun origin() = Offset(size.width / 2f, size.height / 2f) + viewport.pan -
                    Offset(frame.width / 2, frame.height / 2) * scale()

                detectTapGestures(
                    onLongPress = { press ->
                        currentEditing?.onLongPress(frame.unproject((press - origin()) / scale()))
                    },
                    onTap = { tap ->
                        val edit = currentEditing
                        val reach = ENTRANCE_REACH_DP.dp.toPx()
                        val entrance = currentEntrances.takeIf { edit != null }
                            ?.minByOrNull { (_, at) -> (origin() + at * scale() - tap).getDistance() }
                            ?.takeIf { (_, at) -> (origin() + at * scale() - tap).getDistance() <= reach }
                        if (edit != null && entrance != null) {
                            edit.onTapEntrance(entrance.first)
                            return@detectTapGestures
                        }
                        val point = frame.unproject((tap - origin()) / scale())
                        // The top one is what's seen there, since shapes are in drawing order
                        geometry.shapes
                            .lastOrNull { (edit != null || it.label != null) && point in it.feature.rings.orEmpty() }
                            ?.let { currentOnTap(it.feature) }
                    },
                )
            }
            .pointerInput(viewport) {
                detectTransformGestures { centroid, delta, gestureZoom, _ ->
                    val zoom = viewport.zoom
                    val pan = viewport.pan
                    val updated = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                    // Keeps the spot between the fingers in place
                    val fromCenter = centroid - Offset(size.width / 2f, size.height / 2f)
                    viewport.move(updated, fromCenter - (fromCenter - pan) * (updated / zoom) + delta, size.toSize())
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
            fun fill(shape: Shape) {
                drawPath(shape.path, fills.getValue(shape.layer))
                when (shape.feature.id) {
                    in free -> drawPath(shape.path, freeFill.copy(alpha = FREE_ALPHA))
                    in bookable -> drawPath(shape.path, accent.copy(alpha = BOOKABLE_ALPHA))
                }
                if (shape.feature.id == focused) drawPath(shape.path, accent.copy(alpha = FOCUS_ALPHA))
            }

            // Whatever the rooms then leave uncovered is wall, thick as it really is
            geometry.outline?.let { drawPath(it, wall) }
            geometry.belowHallways.forEach { shape ->
                fill(shape)
                drawPath(shape.path, wall, style = line)
            }
            geometry.hallways.forEach(::fill)
            // In metres, as the seams are: this has to cover them whatever the zoom
            drawPath(
                geometry.hallwaySeams,
                fills.getValue(Layer.HALLWAY),
                style = Stroke(width = max(SEAM_WIDTH_METRES, line.width)),
            )
            drawPath(geometry.hallwayWalls, wall, style = line)
            geometry.aboveHallways.forEach { shape ->
                fill(shape)
                drawPath(shape.path, wall, style = line)
            }
            geometry.outline?.let { drawPath(it, wall, style = line) }
        }

        val chipFor = { id: Long ->
            when (id) {
                in free -> freeChip
                in bookable -> bookableChip
                else -> null
            }
        }
        drawLabels(geometry, scale, Offset(originX, originY), chipFor) { text ->
            labels.getOrPut(text) { measurer.measure(text, labelStyle) }
        }

        drawFloorIcons(geometry, stairLinks, scale, Offset(originX, originY), icons, iconTint)

        // Out here too, so that a door doesn't grow into the size of a room when zoomed in
        entrances.forEach { (entrance, at) ->
            val diameter = ENTRANCE_DP.dp.toPx()
            val middle = Offset(originX, originY) + at * scale
            drawCircle(if (entrance.main) accent else wall, diameter / 2, middle)
            val icon = diameter * ENTRANCE_ICON_SCALE
            val angle = entrance.directionOnMap(building.bearing)
            // The arrow's drawn pointing right
            rotate((angle ?: 90.0).toFloat() - 90f, middle) {
                translate(middle.x - icon / 2, middle.y - icon / 2) {
                    with(if (angle != null) directionIcon else entranceIcon) {
                        draw(Size(icon, icon), colorFilter = ColorFilter.tint(onAccent))
                    }
                }
            }
        }
    }
}

/** Shows where north is on a map drawn with [bearing] pointing up. The coloured half points north. */
@Composable
fun NorthArrow(bearing: Double, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val north = colors.primary
    val south = colors.outline
    Surface(
        modifier.size(36.dp).semantics { contentDescription = "North arrow" },
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
    ) {
        Canvas(Modifier.padding(7.dp).rotate(-bearing.toFloat())) {
            val halfWidth = size.width * NEEDLE_WIDTH / 2
            fun half(tipY: Float) = Path().apply {
                moveTo(center.x, tipY)
                lineTo(center.x + halfWidth, center.y)
                lineTo(center.x - halfWidth, center.y)
                close()
            }
            drawPath(half(0f), north)
            drawPath(half(size.height), south)
        }
    }
}

private fun fitScale(frame: BuildingFrame, size: Size): Float =
    min(size.width / frame.width, size.height / frame.height) * MARGIN

/**
 * Outside the map's transform, so that text keeps its size; shown once it fits its room. [chipFor]
 * picks out features to label with a chip, which go on top of the plain labels.
 */
private fun DrawScope.drawLabels(
    geometry: FloorGeometry,
    scale: Float,
    origin: Offset,
    chipFor: (Long) -> Chip?,
    measure: (String) -> TextLayoutResult,
) {
    geometry.shapes.sortedBy { chipFor(it.feature.id) != null }.forEach { shape ->
        val text = shape.feature.drawnLabel ?: return@forEach
        val at = shape.labelAt ?: return@forEach
        val room = shape.side * scale
        if (room < MIN_LABEL_ROOM_DP * density) return@forEach
        val x = origin.x + at.x * scale
        val y = origin.y + at.y * scale
        if (x !in -room..size.width + room || y !in -room..size.height + room) return@forEach
        val layout = measure(text)
        if (layout.size.width > room) return@forEach
        val topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f)
        val chip = chipFor(shape.feature.id)
        if (chip != null) {
            val padding = Offset(CHIP_PADDING_X_DP * density, CHIP_PADDING_Y_DP * density)
            drawRoundRect(
                chip.background,
                topLeft = topLeft - padding,
                size = Size(layout.size.width + padding.x * 2, layout.size.height + padding.y * 2),
                cornerRadius = CornerRadius(CHIP_CORNER_DP * density),
            )
        }
        drawText(layout, color = chip?.text ?: Color.Unspecified, topLeft = topLeft)
    }
}

private class MapIcons(
    val stairs: Painter,
    val lift: Painter,
    val up: Painter,
    val down: Painter,
    val upDown: Painter,
    val info: Painter,
    val toilet: Painter,
    val toiletMen: Painter,
    val toiletWomen: Painter,
    val toiletAccessible: Painter,
) {
    /** Most important first, so that the arrow's what gives way in a tight spot. */
    fun of(shape: Shape, links: Map<Long, StairLinks>): List<Painter> = when {
        "Reception" in shape.feature.typeNames -> listOf(info)
        shape.layer == Layer.LIFT -> listOf(lift)
        shape.layer == Layer.STAIRS -> listOfNotNull(stairs, links[shape.feature.id]?.let(::arrow))
        shape.layer == Layer.TOILET -> listOf(toiletOf(shape.feature))
        else -> emptyList()
    }

    private fun toiletOf(feature: MapFeature): Painter = when {
        "Toilet - Male" in feature.typeNames -> toiletMen
        "Toilet - Female" in feature.typeNames -> toiletWomen
        "Toilet - Accessible" in feature.typeNames -> toiletAccessible
        else -> toilet
    }

    private fun arrow(link: StairLinks): Painter = when {
        link.up && link.down -> upDown
        link.up -> up
        else -> down
    }
}

/** Like labels, these keep their size and only show once they fit. */
private fun DrawScope.drawFloorIcons(
    geometry: FloorGeometry,
    links: Map<Long, StairLinks>,
    scale: Float,
    origin: Offset,
    icons: MapIcons,
    tint: Color,
) {
    val icon = FLOOR_ICON_DP.dp.toPx()
    geometry.shapes.forEach { shape ->
        val at = shape.labelAt ?: return@forEach
        val fitting = icons.of(shape, links).take((shape.side * scale / icon).toInt())
        val left = origin.x + at.x * scale - icon * fitting.size / 2
        val top = origin.y + at.y * scale - icon / 2
        fitting.forEachIndexed { index, painter ->
            translate(left + icon * index, top) {
                with(painter) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(tint)) }
            }
        }
    }
}

private const val MIN_LABEL_ROOM_DP = 16
private const val FLOOR_ICON_DP = 18
private const val CHIP_PADDING_X_DP = 6
private const val CHIP_PADDING_Y_DP = 2
private const val CHIP_CORNER_DP = 6
private val VERTICAL_LIGHT = Color(0xFF9AD9A8)
private val VERTICAL_DARK = Color(0xFF2E6B45)

/** Not the theme's blue, which is what lecture halls are. */
private val TOILET_LIGHT = Color(0xFF8FCDF2)
private val TOILET_DARK = Color(0xFF28618A)
private const val NEEDLE_WIDTH = 0.4f
