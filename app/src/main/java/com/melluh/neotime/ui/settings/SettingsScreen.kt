package com.melluh.neotime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melluh.neotime.AppContainer
import com.melluh.neotime.BuildConfig
import com.melluh.neotime.auth.SessionState
import com.melluh.neotime.timeedit.DataSource
import com.melluh.neotime.timeedit.TIMEEDIT_ZONE
import com.melluh.neotime.ui.account
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onLogin: () -> Unit) {
    val session by container.session.state.collectAsStateWithLifecycle()
    val snapshot by container.schedule.snapshot.collectAsStateWithLifecycle()
    val scope = container.scope

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account and settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Privacy", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your data never leaves your device. neotime has no servers, accounts or analytics, " +
                            "so your schedule, favorites and login are stored only on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "The app only connects directly to TimeEdit and UT's sign-in to load your schedule and book rooms, " +
                            "to UT's people pages and their image host (utwente.becdn.net) to find lecturers and their photos, " +
                            "and to MazeMap (api.mazemap.com) to load floor maps. MazeMap only sees which floors you open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val account = session.account
            if (account == null) {
                NotLoggedInCard(onLogin)
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Logged in as", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(account.displayName ?: account.studentNumber ?: "UT account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(account.studentNumber, account.email).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                        Text(sessionLine(session), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (session is SessionState.NeedsInteractiveLogin) Button(onClick = onLogin) { Text("Log in again") }
                        OutlinedButton(onClick = { scope.launch { container.session.logout(); container.schedule.refresh() } }) {
                            Text("Log out")
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Schedule data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (snapshot.source) {
                            DataSource.FULL -> "Full: rooms, lecturers and locations (logged in)"
                            DataSource.PUBLIC -> "Public: rooms only for the coming weeks, no lecturers"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Last updated: " + (snapshot.fetchedAt?.let(::format) ?: "never"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${snapshot.events.size} activities cached on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (BuildConfig.DEBUG) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Debug", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = {
                            scope.launch { container.session.debugExpireTimeEditSession(); container.schedule.refresh() }
                        }) { Text("Expire TimeEdit session") }
                        OutlinedButton(onClick = {
                            scope.launch { container.session.debugClearAllLoginCookies(); container.schedule.refresh() }
                        }) { Text("Clear all login cookies") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotLoggedInCard(onLogin: () -> Unit) {
    Card(
        onClick = onLogin,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Not logged in", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Log in with your UT account to see rooms and lecturers for the whole year. " +
                        "You sign in on UT's own page; neotime never sees your password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

private fun format(instant: Instant): String = instant.atZone(TIMEEDIT_ZONE).format(TIME)

private fun sessionLine(state: SessionState): String = when (state) {
    is SessionState.LoggedIn -> state.tokenExpiresAt?.let { "TimeEdit sign-in valid until ${format(it)}, renewed automatically" }
        ?: "Signed in"
    is SessionState.Renewing -> "${state.step}…"
    is SessionState.NeedsInteractiveLogin -> "Session expired: UT needs you to sign in again"
    SessionState.LoggedOut -> ""
}
