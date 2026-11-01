package com.melluh.neotime.ui.picker

import com.melluh.neotime.timeedit.CoursePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartsSummaryTest {
    private val parts = listOf("Design Sketching", "Rendering for Communication", "Product Life Cycle Management")

    @Test
    fun `counts the parts still shown`() {
        val hidden = setOf(CoursePart("202500223", "Design Sketching"))
        assertEquals("2 of 3 parts shown", partsSummary("202500223", parts, hidden))
    }

    @Test
    fun `still counts when everything shows`() {
        assertEquals("3 of 3 parts shown", partsSummary("202500223", parts, emptySet()))
    }

    @Test
    fun `a single part reads as singular`() {
        assertEquals("1 of 1 part shown", partsSummary("202500223", listOf("Design Sketching"), emptySet()))
    }

    @Test
    fun `another course's hidden parts don't count`() {
        val hidden = setOf(CoursePart("202500341", "Design Sketching"))
        assertEquals("3 of 3 parts shown", partsSummary("202500223", parts, hidden))
    }

    @Test
    fun `a hidden part that's no longer listed doesn't count`() {
        val hidden = setOf(CoursePart("202500223", "Finished Part"))
        assertEquals("3 of 3 parts shown", partsSummary("202500223", parts, hidden))
    }

    @Test
    fun `a course without parts says nothing`() {
        assertNull(partsSummary("202500223", emptyList(), setOf(CoursePart("202500223", "Design Sketching"))))
        assertNull(partsSummary(null, parts, emptySet()))
    }
}
