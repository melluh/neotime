package com.melluh.neotime.timeedit

import kotlinx.serialization.Serializable

/** One part of a course. Events only carry the course's [code], so that's what identifies it. */
@Serializable
data class CoursePart(val code: String, val name: String)

/** What the schedule shows of a course: its [names] and how many sessions belong to no part. */
data class CourseParts(val names: List<String>, val sessionsWithoutPart: Int)

fun courseParts(code: String?, events: List<Event>): CourseParts {
    val sessions = events.filter { code in it.courseCodes }
    return CourseParts(
        names = sessions.mapNotNull { it.coursePartName }.distinct().sorted(),
        sessionsWithoutPart = sessions.count { it.coursePartName == null },
    )
}

/** Drops the sessions of [hidden] parts. Holidays and sessions without a part always stay. */
fun List<Event>.visible(hidden: Set<CoursePart>): List<Event> {
    if (hidden.isEmpty()) return this
    return filterNot { event ->
        val part = event.coursePartName ?: return@filterNot false
        event.courseCodes.any { CoursePart(it, part) in hidden }
    }
}
