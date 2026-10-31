package com.melluh.neotime.timeedit

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One TimeEdit schedule view ("sid"), with the date range and object types it supports. */
@Serializable
data class ScheduleView(
    val area: Area,
    val sid: Int,
    val label: String,
    val startIso: String,
    val endIso: String,
    val types: Set<Int>,
) {
    val start: LocalDate get() = LocalDate.parse(startIso)
    val end: LocalDate get() = LocalDate.parse(endIso)

    val isRoomViewer: Boolean get() = types == setOf(ObjectType.ROOM.id)

    /** Academic-year views span roughly a year; "upcoming" views only a few weeks. */
    val isYear: Boolean get() = !isRoomViewer && ChronoUnit.DAYS.between(start, end) > 200

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

@Serializable
data class Views(val views: List<ScheduleView>) {
    fun year(area: Area, date: LocalDate): ScheduleView? =
        views.firstOrNull { it.area == area && it.isYear && date in it }

    fun upcoming(area: Area): ScheduleView? =
        views.firstOrNull { it.area == area && !it.isYear && !it.isRoomViewer }

    fun rooms(area: Area): ScheduleView? = views.firstOrNull { it.area == area && it.isRoomViewer }

    companion object {
        /** Known views as of 2026-10-31, used when discovery fails. Ranges per the view pages. */
        val FALLBACK = Views(
            listOf(
                ScheduleView(Area.PUBLIC, 3, "Upcoming schedule", "2026-10-26", "2026-11-29", setOf(5, 6, 14, 32, 59, 66)),
                ScheduleView(Area.PUBLIC, 7, "Academic Year 2025/2026", "2025-09-01", "2026-08-30", setOf(5, 14)),
                ScheduleView(Area.PUBLIC, 10, "Academic Year 2026/2027", "2026-08-31", "2027-08-29", setOf(5, 14)),
                ScheduleView(Area.STUDENT, 13, "Rooster 2025-2026", "2025-09-01", "2026-08-30", setOf(5, 6, 14, 32, 59, 66)),
                ScheduleView(Area.STUDENT, 16, "Rooster 2026-2027", "2026-08-31", "2027-08-29", setOf(5, 6, 14, 32, 59, 66)),
                ScheduleView(Area.STUDENT, 12, "Zalen Viewer", "2026-01-01", "2027-12-31", setOf(4)),
            ),
        )
    }
}

/**
 * Finds the current schedule views by following the links on the area's landing page. View ids
 * change every academic year, so they are discovered instead of hardcoded.
 */
class ViewDiscovery(private val client: TimeEditClient) {

    suspend fun discover(area: Area): List<ScheduleView> {
        val links = landingLinks(client.landing(area))
        return links.mapNotNull { (path, label) -> parseViewPage(area, label, client.page(area, path)) }
    }

    companion object {
        private val SCHEDULE_LINK = Regex("""/web/(?:public|student)/(ri[0-9A-Za-z]+\.html)$""")
        private val DATE = DateTimeFormatter.BASIC_ISO_DATE

        /** Schedule page links on a landing page, as (page, label) pairs. */
        internal fun landingLinks(html: String): List<Pair<String, String>> =
            Jsoup.parse(html).select("a[href]").mapNotNull { a ->
                val page = SCHEDULE_LINK.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                page to a.select("h2").text().ifEmpty { a.text() }.trim()
            }.distinctBy { it.first }

        /** Reads sid, date range and searchable types from a view page; null for non-schedule pages. */
        internal fun parseViewPage(area: Area, label: String, html: String): ScheduleView? {
            val doc = Jsoup.parse(html)
            if (doc.getElementById("reserveformedit") != null) return null // booking page
            val data = doc.getElementById("linksdata") ?: return null
            val sid = data.attr("data-sid").toIntOrNull() ?: return null
            val start = data.attr("data-startLimit").takeIf { it.length == 8 } ?: return null
            val end = data.attr("data-endLimit").takeIf { it.length == 8 } ?: return null
            val types = doc.select("[data-basename][data-id]").mapNotNull { it.attr("data-id").toIntOrNull() }.toSet()
            return ScheduleView(
                area = area,
                sid = sid,
                label = label,
                startIso = LocalDate.parse(start, DATE).toString(),
                endIso = LocalDate.parse(end, DATE).toString(),
                types = types,
            )
        }
    }
}
