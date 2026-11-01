package com.melluh.neotime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val session by container.session.state.collectAsStateWithLifecycle()
    val snapshot by container.schedule.snapshot.collectAsStateWithLifecycle()
    val scope = container.scope
    var showLogoutConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account and settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (session.account != null) {
                        IconButton(onClick = { showLogoutConfirmation = true }) {
                            Icon(Icons.Filled.ExitToApp, contentDescription = "Log out")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val account = session.account
            if (account == null) {
                NotLoggedInCard(onLogin, Modifier.fillMaxWidth())
            } else {
                UserCard(
                    account = account,
                    needsInteractiveLogin = session is SessionState.NeedsInteractiveLogin,
                    onLogin = onLogin,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsNavigationBlock(
                items = listOf(
                    SettingsNavigationItem(
                        title = "Privacy",
                        onClick = onOpenPrivacy,
                        leadingIcon = {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(com.melluh.neotime.R.drawable.ic_privacy_badge),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                    ),
                    SettingsNavigationItem(
                        title = "About this app",
                        onClick = onOpenAbout,
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                    ),
                ),
                modifier = Modifier.fillMaxWidth(),
            )

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

                if (showLogoutConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showLogoutConfirmation = false },
                        text = { Text("Are you sure you want to log out?") },
                        confirmButton = {
                            androidx.compose.material3.Button(
                                onClick = {
                                    showLogoutConfirmation = false
                                    scope.launch { container.session.logout(); container.schedule.refresh() }
                                },
                            ) {
                                Text("Log out")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showLogoutConfirmation = false }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
            }
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
