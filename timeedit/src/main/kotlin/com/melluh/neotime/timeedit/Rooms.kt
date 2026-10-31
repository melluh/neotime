package com.melluh.neotime.timeedit

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A bookable project room. [building] is the name used by the booking page's building filter. */
@Serializable
data class Room(
    val id: String,
    val name: String,
    val capacity: Int? = null,
    val building: String? = null,
    val type: String? = null,
)

data class TimeRange(val start: LocalTime, val end: LocalTime)

/** What the "book a project room" page offers. */
@Serializable
data class BookingView(
    val sid: Int,
    val buildings: List<String>,
    /** How many days past today can be booked. The page's own limits go stale daily. */
    val days: Int,
) {
    companion object {
        /** Known values as of 2026-10-31, used when discovery fails. */
        val FALLBACK = BookingView(
            sid = 4,
            buildings = listOf("Bastille", "Carre", "Horsttoren", "Ravelijn", "Spiegel", "Technohal", "Vrijhof"),
            days = 13,
        )
    }
}

object Availability {
    /** Bookable hours and slot size, as on the booking page. */
    val OPEN: LocalTime = LocalTime.of(8, 0)
    val CLOSE: LocalTime = LocalTime.of(23, 0)
    private const val STEP_MINUTES = 15L

    private val PARENTHESES = Regex("""\s*\(.*$""")
    private val SPACING = Regex("""[\s_]+""")

    /** The next bookable slot start at or after [time]; null once the day's bookable hours are over. */
    fun nextSlot(time: LocalTime): LocalTime? {
        if (!time.isBefore(CLOSE)) return null
        if (time.isBefore(OPEN)) return OPEN
        val minutes = time.toSecondOfDay().toLong().let { (it + 59) / 60 }
        return LocalTime.ofSecondOfDay(((minutes + STEP_MINUTES - 1) / STEP_MINUTES) * STEP_MINUTES * 60)
    }

    /** Bookable time left on [date] around [busy], ignoring anything before [now]. */
    fun freeRanges(busy: List<TimeRange>, date: LocalDate, now: LocalDateTime): List<TimeRange> {
        val from = when {
            date.isBefore(now.toLocalDate()) -> return emptyList()
            date == now.toLocalDate() -> nextSlot(now.toLocalTime()) ?: return emptyList()
            else -> OPEN
        }
        val free = mutableListOf<TimeRange>()
        var cursor = from
        for (period in busy.sortedBy { it.start }) {
            if (period.start.isAfter(cursor)) free += TimeRange(cursor, minOf(period.start, CLOSE))
            if (period.end.isAfter(cursor)) cursor = period.end
        }
        if (cursor.isBefore(CLOSE)) free += TimeRange(cursor, CLOSE)
        return free.filter { it.start.isBefore(it.end) && it.end.toSecondOfDay() - it.start.toSecondOfDay() >= STEP_MINUTES * 60 }
    }

    /**
     * Busy periods per room id on [date]. Reservations carry no room ids, only Hall names, and
     * those differ from the room list, e.g. "VR 275C (7p, Library)" for "VR 275C",
     * "CR 3014 (6p)" for "CR 3014 TNW students only!", "VR 176 1p" for "VR 176", "VR 170" for "VR_170".
     */
    fun busyByRoom(events: List<Event>, rooms: List<Room>, date: LocalDate): Map<String, List<TimeRange>> {
        val names = rooms.map { it to normalize(it.name) }
        val busy = mutableMapOf<String, MutableList<TimeRange>>()
        for (event in events) {
            val range = TimeRange(
                if (event.start.toLocalDate().isBefore(date)) LocalTime.MIN else event.start.toLocalTime(),
                if (event.end.toLocalDate().isAfter(date)) LocalTime.MAX else event.end.toLocalTime(),
            )
            for (hall in event.rooms) {
                val key = normalize(hall.replace(PARENTHESES, ""))
                if (key.isEmpty()) continue
                val exact = names.filter { it.second == key }
                val matches = exact.ifEmpty { names.filter { (_, n) -> n.startsWith("$key ") || key.startsWith("$n ") } }
                for ((room, _) in matches) busy.getOrPut(room.id) { mutableListOf() } += range
            }
        }
        return busy
    }

    private fun normalize(name: String): String = name.replace(SPACING, " ").trim()
}
