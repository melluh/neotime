package com.melluh.neotime.ui.picker

import com.melluh.neotime.timeedit.ObjectRef
import org.junit.Assert.assertEquals
import org.junit.Test

class SectionsTest {
    private fun titles(vararg refs: ObjectRef) = sections(refs.toList()).map { it.title }

    @Test
    fun `groups come first, then periods newest first`() {
        val titles = titles(
            ObjectRef("1.5", "Old, 202000001, 2025, 2B"),
            ObjectRef("2.5", "Early, 202000002, 2026, 1A"),
            ObjectRef("3.6", "TCS MOD02 Group 01"),
            ObjectRef("4.5", "Late, 202000003, 2026, 1B"),
            ObjectRef("5.5", "Later, 202000004, 2026, 2A"),
        )
        assertEquals(listOf("Groups", "2026, 2A", "2026, 1B", "2026, 1A", "2025, 2B"), titles)
    }

    @Test
    fun `study programmes count as groups`() {
        assertEquals(listOf("Groups"), titles(ObjectRef("1.14", "Industrial Design Engineering")))
    }

    @Test
    fun `both name orders land in the same period`() {
        val sections = sections(
            listOf(
                ObjectRef("1.5", "Calculus 1 for AT, 202600368, 1A, 2026"),
                ObjectRef("2.5", "Get Data, 202500215, 2026, 1A"),
            ),
        )
        assertEquals(listOf("2026, 1A"), sections.map { it.title })
        assertEquals(listOf("1.5", "2.5"), sections.single().refs.map { it.id })
    }

    @Test
    fun `a year without a block is still a period`() {
        assertEquals(listOf("2026"), titles(ObjectRef("1.5", "Thesis, 202000001, 2026")))
    }

    @Test
    fun `a year-only period sorts after that year's blocks`() {
        val titles = titles(ObjectRef("1.5", "Thesis, 202000001, 2026"), ObjectRef("2.5", "Get Data, 202500215, 2026, 1A"))
        assertEquals(listOf("2026, 1A", "2026"), titles)
    }

    @Test
    fun `courses without a period come last`() {
        val titles = titles(ObjectRef("1.5", "Introduction to Programming. 202500342"), ObjectRef("2.5", "Get Data, 202500215, 2026, 1A"))
        assertEquals(listOf("2026, 1A", "Other"), titles)
    }

    @Test
    fun `an empty schedule has no sections`() {
        assertEquals(emptyList<Section>(), sections(emptyList()))
    }
}
