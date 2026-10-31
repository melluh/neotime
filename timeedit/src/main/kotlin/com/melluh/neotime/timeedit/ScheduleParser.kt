package com.melluh.neotime.timeedit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Raw shape of TimeEdit's ri.json / my.json responses. */
@Serializable
internal data class RawSchedule(
    val columnheaders: List<String> = emptyList(),
    val info: RawInfo = RawInfo(),
    val reservations: List<RawReservation> = emptyList(),
)

@Serializable
internal data class RawInfo(val reservationlimit: Int = 0, val reservationcount: Int = 0)

@Serializable
internal data class RawReservation(
    val id: String,
    val startdate: String,
    val starttime: String,
    val enddate: String,
    val endtime: String,
    val columns: List<String> = emptyList(),
)

data class ParsedSchedule(
    val events: List<Event>,
    /** True when TimeEdit cut the result off at its reservation limit. */
    val truncated: Boolean,
)

object ScheduleParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Column headers as TimeEdit names them, in English (l=en_US) and Dutch (account default). */
    private enum class Column(vararg val names: String) {
        COURSE("Course", "Cursus"),
        COURSE_PART("Course part", "Cursusonderdeel"),
        ACTIVITY_TYPE("Activity type", "Werkvorm"),
        RESIT("Resit", "Herkansing"),
        HALL("Hall", "Zaal"),
        BUILDING("Building", "Gebouw"),
        LECTURER("Lecturer", "Docent"),
        EXTRA_INFO("Additional information", "Extra informatie"),
        LOCATION("Location", "Locatie"),
    }

    fun parse(body: String): ParsedSchedule {
        val raw = json.decodeFromString<RawSchedule>(body)
        val index = Column.entries.associateWith { col ->
            raw.columnheaders.indexOfFirst { header -> col.names.any { it.equals(header.trim(), ignoreCase = true) } }
        }
        fun RawReservation.col(c: Column): String? =
            index.getValue(c).takeIf { it >= 0 }?.let { columns.getOrNull(it) }?.let(::clean)?.ifEmpty { null }

        val events = raw.reservations.map { r ->
            Event(
                id = r.id,
                start = LocalDateTime.of(parseDate(r.startdate), parseTime(r.starttime)),
                end = LocalDateTime.of(parseDate(r.enddate), parseTime(r.endtime)),
                course = r.col(Column.COURSE).orEmpty(),
                coursePart = r.col(Column.COURSE_PART),
                activityType = r.col(Column.ACTIVITY_TYPE),
                rooms = splitList(r.col(Column.HALL)),
                building = r.col(Column.BUILDING),
                lecturers = splitList(r.col(Column.LECTURER)),
                locationUrl = r.col(Column.LOCATION)?.takeIf { it.startsWith("http") },
                extraInfo = r.col(Column.EXTRA_INFO),
                resit = r.col(Column.RESIT) != null,
            )
        }
        val truncated = raw.info.reservationlimit > 0 && raw.info.reservationcount >= raw.info.reservationlimit
        return ParsedSchedule(events, truncated)
    }

    private val DMY = Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})""")
    private val YMD = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")

    /** The date format follows the account's setting: dd/mm/yyyy, dd-mm-yyyy or yyyy-mm-dd. */
    internal fun parseDate(value: String): LocalDate {
        val v = value.trim()
        YMD.matchEntire(v)?.destructured?.let { (y, m, d) -> return LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }
        DMY.matchEntire(v)?.destructured?.let { (d, m, y) -> return LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }
        throw TimeEditException("Unrecognised date: $value")
    }

    internal fun parseTime(value: String): LocalTime {
        val (h, m) = value.trim().split(':').map { it.toInt() }
        // TimeEdit can end an activity at 24:00
        return if (h == 24) LocalTime.MAX else LocalTime.of(h, m)
    }

    /** TimeEdit pads names with runs of spaces, e.g. "F.J.   Castor de Lima Filho". */
    internal fun clean(value: String): String = value.replace(Regex("""\s+"""), " ").trim()

    /** Splits on commas, except inside parentheses: halls can be "VR 275C (7p, Library)". */
    internal fun splitList(value: String?): List<String> {
        if (value == null) return emptyList()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in value) {
            when (c) {
                '(' -> depth++
                ')' -> depth = maxOf(0, depth - 1)
            }
            if (c == ',' && depth == 0) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        parts += current.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
