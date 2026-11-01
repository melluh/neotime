package com.melluh.neotime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.melluh.neotime.AppContainer
import com.melluh.neotime.R
import com.melluh.neotime.auth.SessionState
import com.melluh.neotime.timeedit.Account
import com.melluh.neotime.ui.login.LoginScreen
import com.melluh.neotime.ui.map.MapScreen
import com.melluh.neotime.ui.picker.PickerScreen
import com.melluh.neotime.ui.rooms.RoomsScreen
import com.melluh.neotime.ui.schedule.ScheduleScreen
import com.melluh.neotime.ui.settings.SettingsScreen
import kotlinx.coroutines.delay

@Composable
fun NeotimeRoot(container: AppContainer) {
    val nav = rememberNavController()
    val session by container.session.state.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) { if (session !is SessionState.NeedsInteractiveLogin) dismissed = false }
    LaunchedEffect(Unit) {
        container.session.notices.collect {
            notice = it
            delay(2500)
            notice = null
        }
    }

    val openLogin = { nav.navigate("login") { launchSingleTop = true } }
    val openTab = { tab: String ->
        nav.navigate(tab) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    val onTab = route == "schedule" || route == "rooms" || route == "map"

    Scaffold(
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == "schedule",
                        onClick = { openTab("schedule") },
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                        label = { Text("Schedule") },
                    )
                    NavigationBarItem(
                        selected = route == "rooms",
                        onClick = { openTab("rooms") },
                        icon = { Icon(painterResource(R.drawable.ic_meeting_room), contentDescription = null) },
                        label = { Text("Rooms") },
                    )
                    NavigationBarItem(
                        selected = route == "map",
                        onClick = { openTab("map") },
                        icon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                        label = { Text("Map") },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            NavHost(
                nav,
                startDestination = "schedule",
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            ) {
                composable("schedule") {
                    ScheduleScreen(
                        container = container,
                        onOpenPicker = { nav.navigate("picker") },
                        onOpenSettings = { nav.navigate("settings") },
                        onOpenMap = { openTab("map") },
                    )
                }
                composable("rooms") {
                    RoomsScreen(
                        container = container,
                        onOpenSettings = { nav.navigate("settings") },
                        onLogin = openLogin,
                        onOpenMap = { openTab("map") },
                    )
                }
                composable("map") {
                    MapScreen(
                        container = container,
                        onOpenSettings = { nav.navigate("settings") },
                        onOpenRooms = { openTab("rooms") },
                    )
                }
                composable("picker") { PickerScreen(container, onBack = { nav.popBackStack() }) }
                composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }, onLogin = openLogin) }
                composable("login") { LoginScreen(container, onDone = { nav.popBackStack() }) }
            }
            if (route != "login") {
                SessionBanner(
                    state = session,
                    notice = notice,
                    dismissed = dismissed,
                    onLogin = openLogin,
                    onDismiss = { dismissed = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(if (onTab) Modifier.padding(padding) else Modifier.navigationBarsPadding())
                        .padding(16.dp)
                        .widthIn(max = 560.dp),
                )
            }
        }
    }
}

/**
 * Floating, non-blocking status for background account activity. The app stays usable
 * underneath; renewal is never silent.
 */
@Composable
fun SessionBanner(
    state: SessionState,
    notice: String?,
    dismissed: Boolean,
    onLogin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val needsLogin = state is SessionState.NeedsInteractiveLogin && !dismissed
    val visible = state is SessionState.Renewing || needsLogin || notice != null
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val colors = if (needsLogin) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)
        }
        Card(
            onClick = { if (needsLogin) onLogin() },
            colors = colors,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                when {
                    state is SessionState.Renewing -> {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "${state.step}…",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                        )
                    }
                    needsLogin -> {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            "TimeEdit session expired. Tap to log in again",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    notice != null -> Text(
                        notice,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

val SessionState.account: Account?
    get() = when (this) {
        is SessionState.LoggedIn -> account
        is SessionState.Renewing -> account
        is SessionState.NeedsInteractiveLogin -> account
        SessionState.LoggedOut -> null
    }

/** Shows who is logged in; tapping opens settings. */
@Composable
fun AccountChip(state: SessionState, onClick: () -> Unit) {
    val account = state.account
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (account != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        if (account == null) {
            Text(
                text = "Log in",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                val initial = account.initial()
                if (initial != null) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = "Account",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** The first letter of the display name, or null when there is no name to take it from. */
fun Account.initial(): String? = displayName?.firstOrNull { it.isLetter() }?.uppercase()
