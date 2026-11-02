package com.melluh.neotime.ui.rooms

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.melluh.neotime.data.RoomStatus
import com.melluh.neotime.timeedit.Availability
import com.melluh.neotime.timeedit.TimeRange
import java.time.LocalTime

private val TRACK = 12.dp
internal val BAR_HEIGHT = 20.dp

private val AXIS_HOURS = listOf(8, 12, 16, 20)

private data class BarColors(
    val free: Color,
    val blocked: Color,
    val past: Color,
    val window: Color,
    val now: Color,
    val dimmed: Color,
)

@Composable
private fun barColors() = MaterialTheme.colorScheme.run {
    BarColors(
        free = primaryContainer,
        blocked = onSurfaceVariant,
        past = onSurface.copy(alpha = 0.08f),
        window = primary,
        now = error,
        dimmed = surface.copy(alpha = 0.5f),
    )
}

/** Where [time] falls between opening and closing, from 0 to 1. */
private fun position(time: LocalTime): Float {
    val open = Availability.OPEN.toSecondOfDay()
    val close = Availability.CLOSE.toSecondOfDay()
    return ((time.toSecondOfDay() - open).toFloat() / (close - open)).coerceIn(0f, 1f)
}

/**
 * A room's bookable day from left to right, with reservations blocked out.
 * [past] greys out what's gone today; [window] outlines the time being looked for.
 * [now] marks the time of day instead, keeping what's gone readable but dimmed.
 */
@Composable
fun AvailabilityBar(
    status: RoomStatus,
    window: TimeRange?,
    past: LocalTime?,
    modifier: Modifier = Modifier,
    now: LocalTime? = null,
) {
    val colors = barColors()
    val description = when {
        status.free.isEmpty() -> "Fully booked"
        else -> "Free " + status.free.joinToString(", ", transform = ::rangeLabel)
    }
    Canvas(modifier.fillMaxWidth().height(BAR_HEIGHT).semantics { contentDescription = description }) {
        val top = (size.height - TRACK.toPx()) / 2
        val corner = CornerRadius(4.dp.toPx())
        val track = Path().apply {
            addRoundRect(RoundRect(Rect(0f, top, size.width, top + TRACK.toPx()), corner))
        }
        fun span(from: Float, to: Float, color: Color) = drawRect(
            color,
            topLeft = Offset(from * size.width, top),
            size = Size((to - from) * size.width, TRACK.toPx()),
        )
        clipPath(track) {
            val gone = past?.let(::position) ?: 0f
            span(0f, gone, colors.past)
            span(gone, 1f, colors.free)
            status.blocked.forEach {
                val from = maxOf(position(it.start), gone)
                val to = position(it.end)
                if (to > from) span(from, to, colors.blocked)
            }
            now?.let { span(0f, position(it), colors.dimmed) }
        }
        // Before opening or after closing, the bar's ends would pass for the time it is
        now?.takeIf { it in Availability.OPEN..Availability.CLOSE }?.let {
            val x = position(it) * size.width
            drawLine(colors.now, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(colors.now, radius = 3.dp.toPx(), center = Offset(x, 3.dp.toPx()))
        }
        window?.let {
            val stroke = 2.dp.toPx()
            val left = position(it.start) * size.width
            val width = (position(it.end) - position(it.start)) * size.width
            drawRoundRect(
                colors.window,
                topLeft = Offset(left.coerceAtMost(size.width - stroke) + stroke / 2, stroke / 2),
                size = Size((width - stroke).coerceAtLeast(0f), size.height - stroke),
                cornerRadius = corner,
                style = Stroke(stroke),
            )
        }
    }
}

/** Hour labels lined up with the [AvailabilityBar]s below it. */
@Composable
fun TimeAxis(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        AXIS_HOURS.forEach { hour ->
            Text(
                "%02d:00".format(hour),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = maxWidth * position(LocalTime.of(hour, 0))),
            )
        }
    }
}

/** Explains the [AvailabilityBar] colors. [showPast] adds the entry that only applies today. */
@Composable
fun BarLegend(showPast: Boolean, modifier: Modifier = Modifier) {
    val colors = barColors()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem("Free", Modifier.background(colors.free, RoundedCornerShape(2.dp)))
        LegendItem("Booked", Modifier.background(colors.blocked, RoundedCornerShape(2.dp)))
        if (showPast) LegendItem("Past", Modifier.background(colors.past, RoundedCornerShape(2.dp)))
        LegendItem("Your time", Modifier.border(2.dp, colors.window, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun LegendItem(label: String, swatch: Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(swatch.size(12.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun rangeLabel(range: TimeRange): String = "${range.start}–${range.end}"
