package com.melluh.neotime.ui.picker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.melluh.neotime.AppContainer
import com.melluh.neotime.timeedit.CoursePart
import com.melluh.neotime.timeedit.CourseParts
import com.melluh.neotime.timeedit.ObjectRef
import com.melluh.neotime.timeedit.courseParts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class CourseDetail(
    val ref: ObjectRef? = null,
    val parts: CourseParts = CourseParts(emptyList(), 0),
    val hidden: Set<CoursePart> = emptySet(),
)

class CourseDetailViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    internal val detail: StateFlow<CourseDetail> = combine(
        container.schedule.selection,
        container.schedule.snapshot,
        container.schedule.hiddenParts,
    ) { selection, snapshot, hidden ->
        val ref = selection.firstOrNull { it.id == id }
        CourseDetail(ref, courseParts(ref?.courseCode, snapshot.events), hidden)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CourseDetail())

    fun setShown(code: String, name: String, shown: Boolean) {
        container.scope.launch { container.schedule.setPartHidden(CoursePart(code, name), hidden = !shown) }
    }
}

/** One course on the schedule: its parts, each of which can be taken off the schedule. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(container: AppContainer, id: String, onBack: () -> Unit) {
    val vm = viewModel { CourseDetailViewModel(container, id) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val ref = detail.ref

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ref?.name?.substringBefore(',').orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ref?.name?.substringAfter(',', "")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Message(it, Modifier.padding(horizontal = 16.dp))
            }
            Parts(detail, onShownChange = vm::setShown)
        }
    }
}

@Composable
private fun Parts(detail: CourseDetail, onShownChange: (code: String, name: String, shown: Boolean) -> Unit) {
    val code = detail.ref?.courseCode ?: return
    val parts = detail.parts
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Text(
                "Parts on your schedule",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        items(parts.names, key = { it }) { name ->
            val shown = CoursePart(code, name) !in detail.hidden
            ListItem(
                headlineContent = { Text(name) },
                trailingContent = { Checkbox(checked = shown, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = shown, role = Role.Checkbox) { onShownChange(code, name, it) },
            )
        }
        if (parts.sessionsWithoutPart > 0) {
            item(key = "without-part") {
                Message("Sessions that don't belong to a part always show.", Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
