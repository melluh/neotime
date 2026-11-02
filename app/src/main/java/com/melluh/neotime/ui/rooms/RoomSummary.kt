package com.melluh.neotime.ui.rooms

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.melluh.neotime.data.RoomStatus
import com.melluh.neotime.timeedit.Availability
import java.time.LocalTime

/** A room's day in a few words. [freeNow] means it can be used right away. */
data class RoomSummary(val text: String, val freeNow: Boolean)

/** Sums up today's [status] as of [now]. Past an hour away, a clock reads better than a countdown. */
fun roomSummary(status: RoomStatus, now: LocalTime): RoomSummary {
    val next = status.free.firstOrNull() ?: return RoomSummary(
        if (Availability.firstStart(now) == null) "Closed for today" else "Fully booked today",
        freeNow = false,
    )
    val until = next.end.takeIf { it.isBefore(Availability.CLOSE) }

    // Free time starts at the next slot, so a room with nothing going on is free before it does
    val occupied = now.isBefore(Availability.OPEN) || status.blocked.any { !now.isBefore(it.start) && now.isBefore(it.end) }
    if (!occupied && next.start == Availability.nextSlot(now)) {
        return RoomSummary(until?.let { "Free until $it" } ?: "Free now", freeNow = true)
    }

    val minutes = (next.start.toSecondOfDay() - now.toSecondOfDay() + 59) / 60
    val text = when {
        minutes < COUNTDOWN_MINUTES -> "Free in $minutes min" + until?.let { ", until $it" }.orEmpty()
        else -> "Free from ${next.start}" + until?.let { " - $it" }.orEmpty()
    }
    return RoomSummary(text, freeNow = false)
}

/** The theme has no green of its own, and dynamic color can't be relied on to make one. */
@Composable
fun freeNowColor(): Color = if (isSystemInDarkTheme()) Color(0xFF7BD88F) else Color(0xFF1B7F3B)

private const val COUNTDOWN_MINUTES = 60
