package com.melluh.neotime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.melluh.neotime.R

private class DomainGroup(val purpose: String, val domains: List<String>)

/** Every host the app connects to. Update this when adding a client or a login step. */
private val CONTACTED_DOMAINS = listOf(
    DomainGroup(
        "Schedule, room booking and TimeEdit sign-in",
        listOf("cloud.timeedit.net", "www.timeedit.net", "auth.timeedit.net"),
    ),
    DomainGroup(
        "UT sign-in",
        listOf("*.surfconext.nl", "*.microsoftonline.com", "*.microsoft.com"),
    ),
    DomainGroup(
        "Your course registrations (OSIRIS)",
        listOf("utwente.osiris-student.nl", "osiris.utwente.nl", "osi-auth-server-prd.osiris-link.nl"),
    ),
    DomainGroup(
        "Lecturer profiles and photos",
        listOf("people.utwente.nl", "utwente.becdn.net"),
    ),
    DomainGroup(
        "Floor maps and room locations",
        listOf("api.mazemap.com"),
    ),
)

@Composable
@Preview
fun PrivacyCard(modifier: Modifier = Modifier) {
    var showDomains by remember { mutableStateOf(false) }

    Card(modifier.fillMaxWidth()) {
        // Less padding at the bottom: the link's touch target already adds empty space there
        Row(
            Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_privacy_badge),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Privacy", style = MaterialTheme.typography.titleMedium)
                Text(
                    "All requests are made directly from your device to UT services. Your personal data and login credentials are not shared.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { showDomains = true }, contentPadding = PaddingValues(0.dp)) {
                    Text("More information")
                }
            }
        }
    }

    if (showDomains) ContactedDomainsDialog(onDismiss = { showDomains = false })
}

@Composable
private fun ContactedDomainsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy & security") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("neotime has no servers or analytics of its own. It only connects to these domains:")
                for (group in CONTACTED_DOMAINS) {
                    Column {
                        Text(group.purpose, style = MaterialTheme.typography.titleSmall)
                        for (domain in group.domains) {
                            Text(
                                domain,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                Text(
                    "The sign-in pages are run by SURFconext and Microsoft and may load resources from their own " +
                        "servers. Links to MazeMap and lecturer profiles open in your browser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
