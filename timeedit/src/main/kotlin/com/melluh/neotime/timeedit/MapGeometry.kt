package com.melluh.neotime.timeedit

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How far apart two walls can be and still be the two sides of a bridge. Real walls start around 25 cm. */
private const val MAX_BRIDGE_WIDTH_METRES = 0.2

private const val METRES_PER_DEGREE_LON = 111_320.0
private const val METRES_PER_DEGREE_LAT = 110_540.0

/** The two sides of a bridge end this far apart at most, allowing for it widening a little. */
private const val MAX_MOUTH_WIDTH_METRES = 0.3

private const val MIN_PART_PERIMETER_METRES = 1.0

private const val SAME_POINT_METRES = 0.05

/** About 11° off exactly opposite. */
private const val MAX_OPPOSITE_COS = -0.98

/**
 * Turns [points] into flat metres with y pointing down, [bearing] degrees of the compass pointing
 * up and (0, 0) at the top-left corner of what they cover. Works in Double and leaves narrowing
 * to the caller: relative to the corner, a Float is plenty, which it isn't for a raw 52.24°.
 */
class MapProjection(points: List<GeoPoint>, bearing: Double = 0.0) {
    private val originLon = points.minOf { it.lon }
    private val originLat = points.maxOf { it.lat }
    private val lonScale = cos(Math.toRadians(originLat)) * METRES_PER_DEGREE_LON
    private val sin = sin(Math.toRadians(bearing))
    private val cos = cos(Math.toRadians(bearing))

    private val left = points.minOf { across(it) }
    private val top = points.minOf { down(it) }

    val width: Double = points.maxOf { across(it) } - left
    val height: Double = points.maxOf { down(it) } - top

    private fun east(p: GeoPoint) = (p.lon - originLon) * lonScale
    private fun north(p: GeoPoint) = (p.lat - originLat) * METRES_PER_DEGREE_LAT
    private fun across(p: GeoPoint) = east(p) * cos - north(p) * sin
    private fun down(p: GeoPoint) = -(east(p) * sin + north(p) * cos)

    fun x(p: GeoPoint): Double = across(p) - left
    fun y(p: GeoPoint): Double = down(p) - top

    /** The point drawn at ([x], [y]). */
    fun unproject(x: Double, y: Double): GeoPoint {
        val across = x + left
        val down = y + top
        return GeoPoint(
            lon = originLon + (across * cos - down * sin) / lonScale,
            lat = originLat - (across * sin + down * cos) / METRES_PER_DEGREE_LAT,
        )
    }
}

/**
 * The outline with its bridges taken out, as one ring per part. MazeMap keeps the separate parts
 * of a floor in a single ring by joining them with a sliver, sometimes 150 m long and 1 cm wide,
 * which draws as a line through thin air between two towers.
 *
 * A bridge shows up as two edges running opposite ways nearly on top of each other. Only the part
 * where they overlap goes, as a long outer wall can carry on past the end of a bridge. What's left
 * is then joined back up across the mouths of the bridges.
 */
/** Whether [point] is inside, counting holes and separate parts the way even-odd fill draws them. */
operator fun Rings.contains(point: GeoPoint): Boolean = sumOf { ring ->
    ring.indices.count { i ->
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        (a.lat > point.lat) != (b.lat > point.lat) &&
            point.lon < a.lon + (point.lat - a.lat) / (b.lat - a.lat) * (b.lon - a.lon)
    }
} % 2 == 1

fun Rings.withoutBridges(): Rings {
    val lonScale = cos(Math.toRadians(firstOrNull()?.firstOrNull()?.lat ?: return this)) * METRES_PER_DEGREE_LON

    fun distance(a: GeoPoint, b: GeoPoint) = hypot((a.lon - b.lon) * lonScale, (a.lat - b.lat) * METRES_PER_DEGREE_LAT)

    // These come first: lying against a real wall, a fold looks like one side of a bridge
    val unfolded = map { ring -> ring.withoutFolds(::distance) }.filter { it.size > 3 }
    val edges = unfolded.flatMap { ring ->
        val closed = if (ring.isNotEmpty() && ring.first() != ring.last()) ring + ring.first() else ring
        closed.zipWithNext { a, b -> Edge(a, b, lonScale) }.filter { it.length > 0 }
    }

    var cut = false
    val runs = mutableListOf<MutableList<GeoPoint>>()
    for (edge in edges) {
        val bridged = edges.mapNotNull { edge.overlapWith(it) }
        cut = cut || bridged.isNotEmpty()
        for (piece in edge.piecesOutside(bridged)) {
            val run = runs.lastOrNull()?.takeIf { it.last() == piece.first }
            if (run != null) run += piece.second else runs += mutableListOf(piece.first, piece.second)
        }
    }
    if (!cut) return unfolded

    val rings = mutableListOf<List<GeoPoint>>()
    while (runs.isNotEmpty()) {
        val ring = runs.removeAt(0)
        while (true) {
            val next = runs.minByOrNull { distance(ring.last(), it.first()) } ?: break
            val gap = distance(ring.last(), next.first())
            if (gap > MAX_MOUTH_WIDTH_METRES || gap >= distance(ring.last(), ring.first())) break
            ring += next
            runs.remove(next)
        }
        // The end of a bridge that led nowhere is all that's left of some of these
        if (ring.zipWithNext(::distance).sum() >= MIN_PART_PERIMETER_METRES) rings += ring
    }
    // A ring that doesn't close would be drawn with a line right across the map, which is far
    // worse than the bridge. Nothing does this today, but the data isn't ours.
    return if (rings.all { distance(it.first(), it.last()) <= MAX_MOUTH_WIDTH_METRES }) rings else unfolded
}

/** Takes out the places where a ring doubles straight back on itself, leaving nothing behind. */
private fun List<GeoPoint>.withoutFolds(distance: (GeoPoint, GeoPoint) -> Double): List<GeoPoint> {
    val kept = mutableListOf<GeoPoint>()
    for (point in this) {
        when {
            kept.size >= 2 && distance(kept[kept.size - 2], point) < SAME_POINT_METRES -> kept.removeAt(kept.size - 1)
            kept.isEmpty() || distance(kept.last(), point) >= SAME_POINT_METRES -> kept += point
        }
    }
    return kept
}

private class Edge(val from: GeoPoint, val to: GeoPoint, lonScale: Double) {
    private val startX = from.lon * lonScale
    private val startY = from.lat * METRES_PER_DEGREE_LAT
    private val dx = to.lon * lonScale - startX
    private val dy = to.lat * METRES_PER_DEGREE_LAT - startY
    val length = hypot(dx, dy)

    private fun x(t: Double) = startX + dx * t
    private fun y(t: Double) = startY + dy * t

    private fun position(x: Double, y: Double) = ((x - startX) * dx + (y - startY) * dy) / (length * length)

    private fun distanceTo(x: Double, y: Double): Double {
        val t = position(x, y).coerceIn(0.0, 1.0)
        return hypot(x - x(t), y - y(t))
    }

    private fun at(t: Double): GeoPoint = when (t) {
        0.0 -> from
        1.0 -> to
        else -> GeoPoint(from.lon + (to.lon - from.lon) * t, from.lat + (to.lat - from.lat) * t)
    }

    /** The stretch of this edge, as fractions of it, that [other] runs back along. */
    fun overlapWith(other: Edge): ClosedFloatingPointRange<Double>? {
        if ((dx * other.dx + dy * other.dy) / (length * other.length) > MAX_OPPOSITE_COS) return null
        val a = position(other.x(0.0), other.y(0.0))
        val b = position(other.x(1.0), other.y(1.0))
        val start = max(0.0, min(a, b))
        val end = min(1.0, max(a, b))
        if (end <= start) return null
        val close = other.distanceTo(x(start), y(start)) <= MAX_BRIDGE_WIDTH_METRES &&
            other.distanceTo(x(end), y(end)) <= MAX_BRIDGE_WIDTH_METRES
        return if (close) start..end else null
    }

    fun piecesOutside(ranges: List<ClosedFloatingPointRange<Double>>): List<Pair<GeoPoint, GeoPoint>> {
        val pieces = mutableListOf<Pair<GeoPoint, GeoPoint>>()
        var reached = 0.0
        for (range in ranges.sortedBy { it.start }) {
            if (range.start > reached) pieces += at(reached) to at(range.start)
            reached = max(reached, range.endInclusive)
        }
        if (reached < 1.0) pieces += at(reached) to to
        return pieces
    }
}
