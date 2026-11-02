package com.melluh.neotime.ui.rooms

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.melluh.neotime.data.RoomStatus
import com.melluh.neotime.timeedit.Availability
import java.time.LocalTime

/** A room's day in a few words. [freeNow] means it can be used right away. */
data class RoomSummary(val text: String, val freeNow: Boolean)

/** Sums up today's [status] as of [now]. */
fun roomSummary(status: RoomStatus, now: LocalTime): RoomSummary {
    // A single slot between reservations is too little to call the room free
    val next = status.free.firstOrNull { it.start.plusMinutes(Availability.STEP_MINUTES).isBefore(it.end) } ?: return RoomSummary(
        if (Availability.firstStart(now) == null) "Closed for today" else "Fully booked today",
        freeNow = false,
    )
    val until = next.end.takeIf { it.isBefore(Availability.CLOSE) }

    // Free time starts at the next slot, so a room with nothing going on is free before it does
    val occupied = now.isBefore(Availability.OPEN) || status.blocked.any { !now.isBefore(it.start) && now.isBefore(it.end) }
    if (!occupied && next.start == Availability.nextSlot(now)) {
        return RoomSummary(until?.let { "Free until $it" } ?: "Free now", freeNow = true)
    }

    return RoomSummary("Free from ${next.start}" + until?.let { " - $it" }.orEmpty(), freeNow = false)
}

/** The theme has no green of its own, and dynamic color can't be relied on to make one. */
@Composable
fun freeNowColor(): Color = if (isSystemInDarkTheme()) Color(0xFF7BD88F) else Color(0xFF1B7F3B)
