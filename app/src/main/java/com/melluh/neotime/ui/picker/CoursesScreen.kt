package com.melluh.neotime.ui.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melluh.neotime.AppContainer
import com.melluh.neotime.timeedit.CoursePart
import com.melluh.neotime.timeedit.ObjectRef
import com.melluh.neotime.timeedit.ObjectType
import com.melluh.neotime.timeedit.courseParts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CoursesViewModel(private val container: AppContainer) : ViewModel() {
    val selection: StateFlow<List<ObjectRef>> = container.schedule.selection
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** By object id: what each row says about its course's parts. */
    internal val partRows: StateFlow<Map<String, PartsRow>> = combine(
        container.schedule.selection,
        container.schedule.snapshot,
        container.schedule.hiddenParts,
    ) { selection, snapshot, hidden ->
        selection.associate { ref ->
            val names = courseParts(ref.courseCode, snapshot.events).names
            ref.id to PartsRow(
                summary = partsSummary(ref.courseCode, names, hidden),
                editable = ref.type == ObjectType.COURSE && names.isNotEmpty(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun remove(ref: ObjectRef) = viewModelScope.launch { container.schedule.remove(ref) }
}

internal data class PartsRow(val summary: String?, val editable: Boolean)

/** Null for courses without parts, which includes courses whose parts aren't known yet. */
internal fun partsSummary(code: String?, parts: List<String>, hidden: Set<CoursePart>): String? {
    if (code == null || parts.isEmpty()) return null
    val shown = parts.count { CoursePart(code, it) !in hidden }
    return "$shown of ${parts.size} ${if (parts.size == 1) "part" else "parts"} shown"
}

internal data class Section(val title: String, val refs: List<ObjectRef>)

private data class Period(val year: String, val block: String?) {
    val label: String get() = listOfNotNull(year, block).joinToString(", ")
}

private val YEAR = Regex("""20\d\d""")
private val COURSE_CODE = Regex("""\d{9,}""")

/** TimeEdit puts the year and block last, in either order: "Calculus, 202001212, 2026, 1A" or "..., 1A, 2026". */
private fun periodOf(ref: ObjectRef): Period? {
    val tail = ref.name.split(',').map { it.trim() }.drop(1).takeLast(2)
    val year = tail.lastOrNull(YEAR::matches) ?: return null
    return Period(year, tail.firstOrNull { it != year && !COURSE_CODE.matches(it) })
}

/** Groups first, then courses by period, newest first; courses without a period come last. */
internal fun sections(selection: List<ObjectRef>): List<Section> {
    val (groups, courses) = selection.partition { it.type == ObjectType.STUDENT_SET || it.type == ObjectType.STUDY_PROGRAMME }
    val byPeriod = courses.groupBy(::periodOf)
    val periods = byPeriod.keys.filterNotNull()
        .sortedWith(compareByDescending<Period> { it.year }.thenByDescending { it.block })
    return buildList {
        add(Section("Groups", groups))
        periods.forEach { add(Section(it.label, byPeriod.getValue(it))) }
        add(Section("Other", byPeriod[null].orEmpty()))
    }.filter { it.refs.isNotEmpty() }
}

/** The courses and groups on the current schedule; adding happens on [PickerScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesScreen(container: AppContainer, onBack: () -> Unit, onAdd: () -> Unit, onOpenCourse: (ObjectRef) -> Unit) {
    val vm = viewModel { CoursesViewModel(container) }
    val selection by vm.selection.collectAsStateWithLifecycle()
    val partRows by vm.partRows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Courses and groups") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add courses to schedule") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (selection.isEmpty()) {
                Text(
                    "No courses or groups on your schedule yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                for (section in sections(selection)) {
                    item(key = "section-${section.title}") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(section.refs, key = { it.id }) { ref ->
                        val name = ref.name.substringBefore(',')
                        val row = partRows[ref.id]
                        val editable = row?.editable == true
                        val details = listOfNotNull(
                            ref.name.substringAfter(',', "").trim().takeIf { it.isNotEmpty() },
                            row?.summary,
                        ).joinToString(" · ").takeIf { it.isNotEmpty() }
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = details?.let { { Text(it) } },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (editable) {
                                        IconButton(onClick = { onOpenCourse(ref) }) {
                                            Icon(Icons.Filled.Edit, contentDescription = "Edit parts of $name")
                                        }
                                    }
                                    IconButton(onClick = { vm.remove(ref) }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Remove $name")
                                    }
                                }
                            },
                            modifier = if (editable) {
                                Modifier.clickable(onClickLabel = "Edit parts of $name") { onOpenCourse(ref) }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}
