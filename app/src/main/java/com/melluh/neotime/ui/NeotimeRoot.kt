package com.melluh.neotime.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.DefaultNavTransitions
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
import com.melluh.neotime.ui.picker.CourseDetailScreen
import com.melluh.neotime.ui.picker.CoursesScreen
import com.melluh.neotime.ui.picker.PickerScreen
import com.melluh.neotime.ui.rooms.RoomsScreen
import com.melluh.neotime.ui.schedule.ScheduleScreen
import com.melluh.neotime.ui.settings.SettingsScreen
import com.melluh.neotime.ui.settings.PrivacySettingsScreen
import com.melluh.neotime.ui.settings.AboutSettingsScreen
import com.melluh.neotime.ui.theme.NeotimeTheme
import kotlinx.coroutines.delay

@Composable
fun NeotimeRoot(container: AppContainer) {
    val nav = rememberNavController()
    val session by container.session.state.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var tabBarHeight by remember { mutableStateOf(0.dp) }

    LaunchedEffect(session) { if (session !is SessionState.NeedsInteractiveLogin) dismissed = false }
    LaunchedEffect(Unit) {
        container.session.notices.collect {
            notice = it
            delay(2500)
            notice = null
        }
    }

    val openLogin = { nav.navigate("login") { launchSingleTop = true } }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val onTabs = route == "tabs"
    val slidePx = with(LocalDensity.current) { SettingsSlideDistance.roundToPx() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            nav,
            startDestination = "tabs",
            enterTransition = { settingsEnter(slidePx) { DefaultNavTransitions.enterTransition(this) } },
            exitTransition = { settingsExit(slidePx) { DefaultNavTransitions.exitTransition(this) } },
            popEnterTransition = { settingsPopEnter(slidePx) { DefaultNavTransitions.enterTransition(this) } },
            popExitTransition = { settingsPopExit(slidePx) { DefaultNavTransitions.exitTransition(this) } },
            predictivePopEnterTransition = { swipeEdge ->
                settingsPredictivePopEnter(slidePx) { DefaultNavTransitions.predictivePopEnterTransition(this, swipeEdge) }
            },
            predictivePopExitTransition = { swipeEdge ->
                settingsPredictivePopExit(swipeEdge) { DefaultNavTransitions.predictivePopExitTransition(this, swipeEdge) }
            },
        ) {
            composable("tabs") {
                TabsScreen(
                    container = container,
                    onOpenPicker = { nav.navigate("picker") },
                    onOpenSettings = { nav.navigate("settings") },
                    onLogin = openLogin,
                    onTabBarHeight = { tabBarHeight = it },
                )
            }
            composable("picker") {
                CoursesScreen(
                    container,
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate("picker/add") },
                    onOpenCourse = { nav.navigate("picker/course/${Uri.encode(it.id)}") },
                )
            }
            composable("picker/add") { PickerScreen(container, onBack = { nav.popBackStack() }) }
            composable("picker/course/{id}") { entry ->
                CourseDetailScreen(container, entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onLogin = openLogin,
                    onOpenPrivacy = { nav.navigate("privacy") },
                    onOpenAbout = { nav.navigate("about") },
                )
            }
            composable("privacy") { PrivacySettingsScreen(container, onBack = { nav.popBackStack() }) }
            composable("about") { AboutSettingsScreen(onBack = { nav.popBackStack() }) }
            composable("login") { LoginScreen(container, onDone = { nav.popBackStack() }) }
        }
        if (route != "login" && !(route == "settings" && session is SessionState.NeedsInteractiveLogin)) {
            SessionBanner(
                state = session,
                notice = notice,
                dismissed = dismissed,
                onLogin = openLogin,
                onDismiss = { dismissed = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (onTabs) Modifier.padding(bottom = tabBarHeight) else Modifier.navigationBarsPadding())
                    .padding(16.dp)
                    .widthIn(max = 560.dp),
            )
        }
    }
}

/**
 * The three tabs with their navigation bar. This is one destination of the root [NavHost], so
 * the bar stays part of the page and moves with it when something opens on top, instead of
 * vanishing and resizing the page halfway through the transition.
 */
@Composable
private fun TabsScreen(
    container: AppContainer,
    onOpenPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogin: () -> Unit,
    onTabBarHeight: (Dp) -> Unit,
) {
    val nav = rememberNavController()
    val openTab = { tab: String ->
        nav.navigate(tab) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // The controller only knows its route once the NavHost below has composed. Remembering the
    // last one keeps the bar's selection from animating in each time this page comes back.
    var lastRoute by rememberSaveable { mutableStateOf("schedule") }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route ?: lastRoute
    SideEffect { lastRoute = route }

    Scaffold(
        bottomBar = {
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
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        val barHeight = padding.calculateBottomPadding()
        SideEffect { onTabBarHeight(barHeight) }
        NavHost(
            nav,
            startDestination = "schedule",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable("schedule") {
                ScheduleScreen(
                    container = container,
                    onOpenPicker = onOpenPicker,
                    onOpenSettings = onOpenSettings,
                    onOpenMap = { openTab("map") },
                )
            }
            composable("rooms") {
                RoomsScreen(
                    container = container,
                    onOpenSettings = onOpenSettings,
                    onLogin = onLogin,
                    onOpenMap = { openTab("map") },
                )
            }
            composable("map") {
                MapScreen(
                    container = container,
                    onOpenSettings = onOpenSettings,
                    onOpenRooms = { openTab("rooms") },
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
                            state.step,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                        )
                    }
                    needsLogin -> {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(
                                "Session expired",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "Tap here to log in again.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    notice != null -> {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            notice,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Signing in again", showBackground = true, widthDp = 400)
@Preview(
    name = "Signing in again - Dark",
    showBackground = true,
    widthDp = 400,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SessionBannerRenewingPreview() {
    NeotimeTheme {
        SessionBanner(
            state = SessionState.Renewing("Signing in...", account = null),
            notice = null,
            dismissed = false,
            onLogin = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Signed in again", showBackground = true, widthDp = 400)
@Preview(
    name = "Signed in again - Dark",
    showBackground = true,
    widthDp = 400,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SessionBannerSignedInAgainPreview() {
    NeotimeTheme {
        SessionBanner(
            state = SessionState.LoggedOut,
            notice = "Signed in again",
            dismissed = false,
            onLogin = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Session expired", showBackground = true, widthDp = 400)
@Preview(
    name = "Session expired - Dark",
    showBackground = true,
    widthDp = 400,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SessionBannerNeedsLoginPreview() {
    NeotimeTheme {
        SessionBanner(
            state = SessionState.NeedsInteractiveLogin(account = null),
            notice = null,
            dismissed = false,
            onLogin = {},
            onDismiss = {},
        )
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
