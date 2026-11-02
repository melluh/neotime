package com.melluh.neotime.timeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class CoursePartsTest {
    private val imv = "Information Management and Visualisation. 202500223"

    private fun event(course: String, part: String? = null, id: String = "1") =
        Event(id, LocalDateTime.MIN, LocalDateTime.MIN, course, coursePart = part)

    @Test
    fun `part names lose the code and zero-width space`() {
        assertEquals("Rendering for Communication", event(imv, "Rendering for Communication​ 202500223").coursePartName)
        assertEquals("Design Sketching", event(imv, "Design Sketching 202500223").coursePartName)
        assertEquals("Biomechanica", event(imv, " Biomechanica - 202400511").coursePartName)
    }

    @Test
    fun `a part that is only a code counts as no part`() {
        assertNull(event(imv, "202500223").coursePartName)
        assertNull(event(imv, null).coursePartName)
    }

    @Test
    fun `course codes cover every course on an event and none on a holiday`() {
        val shared = event("IT and Law. 202100211, Cybersecurity and Law Diamond. 202600096")
        assertEquals(setOf("202100211", "202600096"), shared.courseCodes)
        assertEquals(emptySet<String>(), event("Christmas Break").courseCodes)
    }

    @Test
    fun `object names give the course code in either order`() {
        assertEquals("202500223", ObjectRef("1.5", "Information Management, 202500223, 1B, 2026").courseCode)
        assertEquals("202500223", ObjectRef("1.5", "Information Management, 202500223, 2026, 1B").courseCode)
        assertNull(ObjectRef("1.6", "TCS MOD02 Group 01").courseCode)
        assertNull(ObjectRef("1.5", "Calculus 2026").courseCode)
    }

    @Test
    fun `parts are sorted, distinct and only from that course`() {
        val parts = courseParts(
            "202500223",
            listOf(
                event(imv, "Technical Product Modelling 2 202500223"),
                event(imv, "Design Sketching  202500223"),
                event(imv, "Technical Product Modelling 2 202500223"),
                event("Calculus. 202600368", "Exam 202600368"),
                event("Christmas Break"),
            ),
        )
        assertEquals(listOf("Design Sketching", "Technical Product Modelling 2"), parts.names)
        assertEquals(0, parts.sessionsWithoutPart)
    }

    @Test
    fun `a part's own code doesn't decide its course`() {
        val course = "Software Design and Modelling. 202500341"
        val parts = courseParts("202500341", listOf(event(course, "Design Theory 202400356"), event(course)))
        assertEquals(listOf("Design Theory"), parts.names)
        assertEquals(1, parts.sessionsWithoutPart)
    }

    @Test
    fun `an unknown course has no parts`() {
        assertEquals(CourseParts(emptyList(), 0), courseParts("202500223", listOf(event("Christmas Break"))))
        assertEquals(CourseParts(emptyList(), 0), courseParts(null, listOf(event("Christmas Break"))))
    }

    @Test
    fun `hiding a part keeps other parts, part-less sessions and holidays`() {
        val hidden = event(imv, "Design Sketching 202500223", id = "hidden")
        val other = event(imv, "Product Life Cycle Management 202500223", id = "other")
        val partless = event(imv, id = "partless")
        val holiday = event("Christmas Break", id = "holiday")
        val visible = listOf(hidden, other, partless, holiday).visible(setOf(CoursePart("202500223", "Design Sketching")))
        assertEquals(listOf("other", "partless", "holiday"), visible.map { it.id })
    }

    @Test
    fun `the same part name stays in another course`() {
        val theirs = event("Software Design and Modelling. 202500341", "Design Project 202400356")
        val hidden = setOf(CoursePart("202500342", "Design Project"))
        assertEquals(listOf(theirs), listOf(theirs).visible(hidden))
    }

    @Test
    fun `a part hidden under any listed course is hidden`() {
        val shared = event("IT and Law. 202100211, Cybersecurity and Law Diamond. 202600096", "Cryptography 202600096")
        assertEquals(emptyList<Event>(), listOf(shared).visible(setOf(CoursePart("202100211", "Cryptography"))))
    }
}
