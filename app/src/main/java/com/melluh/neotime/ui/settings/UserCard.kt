package com.melluh.neotime.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.melluh.neotime.timeedit.Account
import com.melluh.neotime.ui.initial
import com.melluh.neotime.ui.theme.NeotimeTheme

@Composable
fun UserCard(
    account: Account,
    needsInteractiveLogin: Boolean,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(
            Modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val initial = account.initial()
                        if (initial != null) {
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = "Account",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }

                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        account.displayName ?: account.studentNumber ?: "UT account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(account.email.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (needsInteractiveLogin) {
                Surface(
                    onClick = onLogin,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(Icons.Outlined.Warning, contentDescription = null)
                        Column(
                            Modifier.weight(1f)
                        ) {
                            Text("Session expired", fontWeight = FontWeight.SemiBold)
                            Text("You need to log in again.", style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 400)
@Preview(name = "Dark", showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UserCardPreview() {
    NeotimeTheme {
        UserCard(
            account = Account("Ada Lovelace", "s1234567", "ada@example.com"),
            needsInteractiveLogin = false,
            onLogin = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Interactive login needed - Light", showBackground = true, widthDp = 400)
@Preview(
    name = "Interactive login needed - Dark",
    showBackground = true,
    widthDp = 400,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun UserCardInteractiveLoginPreview() {
    NeotimeTheme {
        UserCard(
            account = Account("Ada Lovelace", "s1234567", "ada@example.com"),
            needsInteractiveLogin = true,
            onLogin = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
