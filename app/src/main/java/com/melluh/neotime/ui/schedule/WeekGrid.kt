package com.melluh.neotime.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melluh.neotime.timeedit.Event
import com.melluh.neotime.timeedit.TIMEEDIT_ZONE
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private val HOUR_HEIGHT = 56.dp
private val TIME_COLUMN = 36.dp

/** One week as a time grid: Mon–Fri, plus weekend days that have events. */
@Composable
fun WeekGrid(monday: LocalDate, events: List<Event>, onEventClick: (Event) -> Unit) {
    val days = remember(monday, events) {
        val weekend = events.map { it.date.dayOfWeek }.filter { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }.toSet()
        (0L..6L).map { monday.plusDays(it) }.filter { it.dayOfWeek.value <= 5 || it.dayOfWeek in weekend }
    }
    val (firstHour, lastHour) = remember(events) {
        val first = events.minOfOrNull { it.start.hour } ?: 8
        val last = events.maxOfOrNull { if (it.end.minute > 0) it.end.hour + 1 else it.end.hour } ?: 18
        minOf(first, 8) to maxOf(last, 18).coerceAtMost(24)
    }
    val today = LocalDate.now(TIMEEDIT_ZONE)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = TIME_COLUMN)) {
            for (day in days) {
                val isToday = day == today
                Column(Modifier.weight(1f).padding(vertical = 6.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.width(TIME_COLUMN)) {
                for (hour in firstHour until lastHour) {
                    Box(Modifier.height(HOUR_HEIGHT)) {
                        Text(
                            "%02d".format(hour),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                        )
                    }
                }
            }
            val gridHeight = HOUR_HEIGHT * (lastHour - firstHour)
            for (day in days) {
                DayColumn(
                    events = events.filter { it.date == day },
                    firstHour = firstHour,
                    height = gridHeight,
                    hours = lastHour - firstHour,
                    onEventClick = onEventClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    events: List<Event>,
    firstHour: Int,
    height: Dp,
    hours: Int,
    onEventClick: (Event) -> Unit,
    modifier: Modifier,
) {
    val placed = remember(events) { layoutLanes(events) }
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    BoxWithConstraints(modifier.height(height)) {
        for (h in 0 until hours) {
            Box(
                Modifier.fillMaxWidth().offset(y = HOUR_HEIGHT * h).height(1.dp).background(lineColor),
            )
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(lineColor))
        val columnWidth = maxWidth
        for (p in placed) {
            val e = p.event
            val startMin = (e.start.hour - firstHour) * 60 + e.start.minute
            val durationMin = java.time.Duration.between(e.start, e.end).toMinutes().coerceAtLeast(15)
            val laneWidth = columnWidth / p.laneCount
            EventBlock(
                event = e,
                onClick = { onEventClick(e) },
                modifier = Modifier
                    .offset(x = laneWidth * p.lane, y = HOUR_HEIGHT * (startMin / 60f))
                    .width(laneWidth)
                    .height(HOUR_HEIGHT * (durationMin / 60f))
                    .padding(1.dp),
            )
        }
    }
}

@Composable
private fun EventBlock(event: Event, onClick: () -> Unit, modifier: Modifier) {
    val color = courseColor(event.courseName, MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    val onColor = if (color.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            event.courseName,
            color = onColor,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val room = event.rooms.joinToString(", ")
        if (room.isNotEmpty()) {
            Text(room, color = onColor, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class Placed(val event: Event, val lane: Int, val laneCount: Int)

/** Assigns overlapping events to side-by-side lanes, per cluster of overlaps. */
private fun layoutLanes(events: List<Event>): List<Placed> {
    val sorted = events.sortedWith(compareBy({ it.start }, { it.end }))
    val result = mutableListOf<Placed>()
    var cluster = mutableListOf<Pair<Event, Int>>()
    var clusterEnd = sorted.firstOrNull()?.end

    fun flush() {
        val lanes = (cluster.maxOfOrNull { it.second } ?: -1) + 1
        cluster.forEach { (e, lane) -> result += Placed(e, lane, lanes) }
        cluster = mutableListOf()
    }

    for (e in sorted) {
        if (clusterEnd != null && !e.start.isBefore(clusterEnd) && cluster.isNotEmpty()) flush()
        val laneEnds = cluster.groupBy { it.second }.mapValues { (_, v) -> v.maxOf { it.first.end } }
        val lane = (0..cluster.size).first { l -> laneEnds[l]?.let { !e.start.isBefore(it) } ?: true }
        cluster += e to lane
        clusterEnd = if (clusterEnd == null || e.end.isAfter(clusterEnd)) e.end else clusterEnd
        if (cluster.size == 1) clusterEnd = e.end
    }
    flush()
    return result
}

/** Stable colour per course, so the same course always looks the same. */
private fun courseColor(name: String, dark: Boolean): Color {
    val hue = (abs(name.hashCode()) % 360).toFloat()
    return if (dark) Color.hsl(hue, 0.35f, 0.35f) else Color.hsl(hue, 0.55f, 0.82f)
}
