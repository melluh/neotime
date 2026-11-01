package com.melluh.neotime.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.melluh.neotime.R
import com.melluh.neotime.ui.theme.NeotimeTheme

data class SettingsNavigationItem(
    val title: String,
    val onClick: () -> Unit,
    val leadingIcon: @Composable () -> Unit,
)

@Composable
fun SettingsNavigationBlock(
    items: List<SettingsNavigationItem>,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        items.forEachIndexed { index, item ->
            SettingsNavigationRow(item)
            if (index < items.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(item: SettingsNavigationItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .height(64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.leadingIcon()
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 400)
@Preview(name = "Dark", showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsNavigationBlockPreview() {
    NeotimeTheme {
        SettingsNavigationBlock(
            items = listOf(
                SettingsNavigationItem(
                    title = "Privacy",
                    onClick = {},
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_privacy_badge),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    },
                ),
                SettingsNavigationItem(
                    title = "About this app",
                    onClick = {},
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
            modifier = Modifier.padding(16.dp),
        )
    }
}
