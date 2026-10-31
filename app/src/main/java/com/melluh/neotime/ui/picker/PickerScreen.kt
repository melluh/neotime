package com.melluh.neotime.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melluh.neotime.AppContainer
import com.melluh.neotime.timeedit.ObjectRef
import com.melluh.neotime.timeedit.ObjectType
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchState(val loading: Boolean = false, val results: List<ObjectRef> = emptyList(), val error: String? = null)

@OptIn(FlowPreview::class)
class PickerViewModel(private val container: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val type = MutableStateFlow(ObjectType.STUDENT_SET)
    val selection: StateFlow<List<ObjectRef>> = container.schedule.selection
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    init {
        viewModelScope.launch {
            combine(query.debounce(400), type) { q, t -> q.trim() to t }.collect { (q, t) ->
                if (q.length < 2) {
                    _search.value = SearchState()
                    return@collect
                }
                _search.value = _search.value.copy(loading = true, error = null)
                _search.value = try {
                    SearchState(results = container.schedule.search(t, q))
                } catch (e: Exception) {
                    SearchState(error = "Search failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun toggle(ref: ObjectRef) = viewModelScope.launch {
        if (selection.value.any { it.id == ref.id }) container.schedule.remove(ref) else container.schedule.add(ref)
    }
}

private val PICKABLE = listOf(ObjectType.STUDENT_SET, ObjectType.COURSE, ObjectType.STUDY_PROGRAMME)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PickerScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = viewModel { PickerViewModel(container) }
    val query by vm.query.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Courses and groups") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (selection.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    for (ref in selection) {
                        InputChip(
                            selected = true,
                            onClick = { vm.toggle(ref) },
                            label = { Text(ref.name.substringBefore(','), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search, e.g. \"TCS MOD02\" or \"Calculus\"") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                for (t in PICKABLE) {
                    FilterChip(selected = type == t, onClick = { vm.type.value = t }, label = { Text(t.label) })
                }
            }
            if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            search.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(search.results, key = { it.id }) { ref ->
                    val added = selection.any { it.id == ref.id }
                    ListItem(
                        headlineContent = { Text(ref.name.substringBefore(',')) },
                        supportingContent = ref.name.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                        trailingContent = {
                            IconButton(onClick = { vm.toggle(ref) }) {
                                Icon(if (added) Icons.Filled.Check else Icons.Filled.Add, contentDescription = if (added) "Remove" else "Add")
                            }
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}
