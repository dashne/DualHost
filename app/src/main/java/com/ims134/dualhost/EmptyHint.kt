package com.ims134.dualhost

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun EmptyHint(
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
    onPermissions: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No clones yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Tap + to add an app from your installed apps. " +
                    "Check permissions first so clones can save files and show notifications.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
        )
        OutlinedButton(onClick = onAdd) { Text("Add an app") }
        OutlinedButton(onClick = onPermissions, modifier = Modifier.padding(top = 8.dp)) {
            Text("Check permissions")
        }
    }
}