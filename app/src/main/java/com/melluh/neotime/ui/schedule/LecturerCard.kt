package com.melluh.neotime.ui.schedule

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.melluh.neotime.AppContainer
import com.melluh.neotime.data.LecturerEntry
import com.melluh.neotime.timeedit.RoomNames
import com.melluh.neotime.ui.theme.NeotimeTheme
import kotlinx.coroutines.launch

/** A lecturer's contact details, over what they were opened from. [name] is [entry]'s key. */
@Composable
internal fun LecturerCard(
    name: String,
    entry: LecturerEntry?,
    container: AppContainer,
    onOpenMap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val match = entry?.match
    val details = entry?.profile?.details
    var loading by remember(match?.profileUrl) { mutableStateOf(match != null) }
    var failed by remember(match?.profileUrl) { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    // The lookup may finish while the card is open, so this also waits for the match
    LaunchedEffect(match?.profileUrl) {
        failed = !container.lecturers.loadProfile(name)
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        LecturerCardContent(
            name = match?.displayName ?: name,
            // The larger photo draws over the one that's already there
            photoUrls = listOfNotNull(match?.photoUrl, details?.photoUrl),
            email = details?.email,
            offices = details?.offices.orEmpty(),
            loading = loading && details == null,
            notice = problem ?: when {
                failed && details == null -> "Couldn't load contact details"
                match == null -> "Couldn't find exactly one person with this name"
                else -> null
            },
            profileLabel = if (match != null) "View on people.utwente.nl" else "Search on people.utwente.nl",
            onEmail = { address ->
                try {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$address".toUri()))
                } catch (e: ActivityNotFoundException) {
                    problem = "No email app found"
                }
            },
            onOffice = { office ->
                scope.launch {
                    problem = container.focus.showOnMap(office, RoomNames.officeIdentifier(office))
                    if (problem == null) onOpenMap()
                }
            },
            onProfile = {
                val url = match?.profileUrl ?: container.lecturers.searchUrl(name)
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            },
        )
    }
}

@Composable
private fun LecturerCardContent(
    name: String,
    photoUrls: List<String>,
    email: String?,
    offices: List<String>,
    loading: Boolean,
    notice: String?,
    profileLabel: String,
    onEmail: (String) -> Unit,
    onOffice: (String) -> Unit,
    onProfile: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            LecturerAvatar(name, photoUrls, size = 128.dp, initialsStyle = MaterialTheme.typography.displaySmall)
            Text(name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Column(Modifier.fillMaxWidth()) {
                email?.let { address ->
                    ContactRow(Icons.Filled.Email, address, clickLabel = "Send an email to $address") { onEmail(address) }
                }
                offices.forEach { office ->
                    // Only some offices can be found on the map, the others are there to read
                    val onClick = if (RoomNames.officeIdentifier(office) != null) ({ onOffice(office) }) else null
                    ContactRow(Icons.Filled.LocationOn, office, clickLabel = "Show $office on map", onClick = onClick)
                }
            }
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            OutlinedButton(onClick = onProfile) { Text(profileLabel) }
        }
    }
}

@Composable
private fun ContactRow(icon: ImageVector, text: String, clickLabel: String, onClick: (() -> Unit)?) {
    val clickable = if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, onClick = onClick) else Modifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().then(clickable).padding(vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Photos draw over the initials, which stay visible while loading or if they can't be decoded. */
@Composable
internal fun LecturerAvatar(name: String, photoUrls: List<String>, size: Dp, initialsStyle: TextStyle) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(initials(name), style = initialsStyle, color = MaterialTheme.colorScheme.onSecondaryContainer)
        photoUrls.forEach { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun initials(name: String): String {
    val words = name.split(' ').filter { it.isNotEmpty() }
    return listOfNotNull(words.firstOrNull(), words.lastOrNull().takeIf { words.size > 1 })
        .joinToString("") { it.take(1).uppercase() }
}

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(name = "Dark", showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LecturerCardPreview() {
    NeotimeTheme {
        LecturerCardContent(
            name = "T. van Dijk",
            photoUrls = emptyList(),
            email = "t.vandijk@utwente.nl",
            offices = listOf("Zilverling 3035", "Capitool 15 022"),
            loading = false,
            notice = null,
            profileLabel = "View on people.utwente.nl",
            onEmail = {},
            onOffice = {},
            onProfile = {},
        )
    }
}
