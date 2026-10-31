package com.melluh.neotime.timeedit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** All TimeEdit times are local to the university. */
val TIMEEDIT_ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

/** Object categories that can be searched and scheduled. */
enum class ObjectType(val id: Int, val label: String) {
    COURSE(5, "Course"),
    STUDENT_SET(6, "Student set"),
    STUDY_PROGRAMME(14, "Study programme"),
    COURSE_PART(59, "Course part"),
    ROOM(4, "Room");

    companion object {
        fun of(id: Int): ObjectType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A schedulable TimeEdit object, identified the way the API does: "<objectId>.<typeId>",
 * e.g. "12335.6" for a student set.
 */
@Serializable
data class ObjectRef(val id: String, val name: String) {
    val typeId: Int get() = id.substringAfterLast('.').toIntOrNull() ?: -1
    val type: ObjectType? get() = ObjectType.of(typeId)
}

/** Where a schedule snapshot came from. */
@Serializable
enum class DataSource {
    /** Logged-in view: rooms, building, lecturers and location for the full year. */
    FULL,

    /** Public views: rooms only for the coming weeks, never lecturers. */
    PUBLIC,
}

@Serializable
data class Event(
    val id: String,
    @Serializable(with = LocalDateTimeSerializer::class) val start: LocalDateTime,
    @Serializable(with = LocalDateTimeSerializer::class) val end: LocalDateTime,
    /** Raw course column, e.g. "Introduction to Programming. 202500342". */
    val course: String,
    val coursePart: String? = null,
    val activityType: String? = null,
    val rooms: List<String> = emptyList(),
    val building: String? = null,
    val lecturers: List<String> = emptyList(),
    /** MazeMap link for the room, when TimeEdit provides one. */
    val locationUrl: String? = null,
    val extraInfo: String? = null,
    val resit: Boolean = false,
) {
    /** Course names without the trailing Osiris code, e.g. "Introduction to Programming". */
    val courseName: String
        get() = course.split(COURSE_SEPARATOR).joinToString(", ") { it.replace(COURSE_CODE, "") }

    val date: LocalDate get() = start.toLocalDate()

    private companion object {
        val COURSE_SEPARATOR = Regex(""",\s(?=\S.*\.\s\d{6,})""")
        val COURSE_CODE = Regex("""\.\s\d{6,}$""")
    }
}

object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString())
}

open class TimeEditException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The request hit TimeEdit's login page instead of content: the session is missing or expired. */
class NotLoggedInException : TimeEditException("Not logged in to TimeEdit")
