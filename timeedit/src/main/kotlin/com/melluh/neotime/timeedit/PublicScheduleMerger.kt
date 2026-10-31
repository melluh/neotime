package com.melluh.neotime.timeedit

/**
 * Combines the two public views: the academic-year view has every activity but no rooms, and
 * the upcoming view has rooms for the next weeks only. Reservation ids are shared between views.
 */
object PublicScheduleMerger {
    fun merge(year: List<Event>, upcoming: List<Event>): List<Event> {
        val withRooms = upcoming.associateBy { it.id }
        val merged = year.map { event ->
            val detailed = withRooms[event.id] ?: return@map event
            event.copy(
                rooms = detailed.rooms.ifEmpty { event.rooms },
                building = detailed.building ?: event.building,
                extraInfo = event.extraInfo ?: detailed.extraInfo,
            )
        }
        val yearIds = year.mapTo(HashSet()) { it.id }
        return (merged + upcoming.filter { it.id !in yearIds }).sortedBy { it.start }
    }
}
