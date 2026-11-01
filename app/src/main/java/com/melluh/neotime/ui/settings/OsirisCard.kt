package com.melluh.neotime.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.melluh.neotime.ui.theme.NeotimeTheme

/**
 * Shown wherever the user decides on OSIRIS access. The course codes are the one part that leaves
 * the device, as TimeEdit searches, so the text has to keep saying so.
 */
const val OSIRIS_CONSENT_EXPLANATION =
    "neotime signs in to OSIRIS with your UT account and only reads your course registrations. " +
        "They stay on this device; only the course codes are sent to TimeEdit, to find the matching courses."

@Composable
fun OsirisCard(allowed: Boolean, onAllowedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Fetch from OSIRIS", style = MaterialTheme.typography.titleMedium)
                Text(
                    OSIRIS_CONSENT_EXPLANATION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = allowed, onCheckedChange = onAllowedChange)
        }
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 400)
@Preview(name = "Dark", showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OsirisCardPreview() {
    NeotimeTheme {
        OsirisCard(allowed = false, onAllowedChange = {}, modifier = Modifier.padding(16.dp))
    }
}
