package com.ims134.dualhost

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ims134.dualhost.ui.theme.DualHostTheme

class AppPickerActivity : ComponentActivity() {

    private val vm: PickerViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.load()
        setContent {
            DualHostTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Add an app") },
                            navigationIcon = { TextButton(onClick = { finish() }) { Text("Back") } }
                        )
                    }
                ) { pad ->
                    Column(Modifier.padding(pad)) {
                        OutlinedTextField(
                            value = vm.query, onValueChange = { vm.query = it },
                            singleLine = true, label = { Text("Search") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        if (vm.loading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            LazyColumn {
                                items(vm.shown, key = { it.pkg }) { a ->
                                    ListItem(
                                        modifier = Modifier.clickable { vm.selected = a },
                                        leadingContent = {
                                            if (a.icon != null) Image(a.icon, null, Modifier.size(44.dp))
                                            else Box(Modifier.size(44.dp))
                                        },
                                        headlineContent = { Text(a.label) },
                                        supportingContent = { Text(a.pkg) },
                                        trailingContent = {
                                            if (a.warning != null) Text(
                                                "may not work",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                val a = vm.selected
                if (a != null) {
                    AlertDialog(
                        onDismissRequest = { vm.selected = null },
                        title = { Text(a.label) },
                        text = {
                            Column {
                                Text(a.pkg, style = MaterialTheme.typography.bodySmall)
                                if (a.warning != null) {
                                    Text("\nMay not work", color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(a.warning)
                                }
                                TextButton(onClick = { vm.showManifest(a) }) { Text("Show manifest") }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val c = vm.add(a)
                                vm.selected = null
                                Toast.makeText(this@AppPickerActivity,
                                    a.label + " added as clone " + CloneStore.list(this@AppPickerActivity).size, Toast.LENGTH_SHORT).show()
                                finish()
                            }) { Text("Add clone") }
                        },
                        dismissButton = { TextButton(onClick = { vm.selected = null }) { Text("Close") } }
                    )
                }

                val m = vm.manifest
                if (m != null) {
                    AlertDialog(
                        onDismissRequest = { vm.manifest = null },
                        title = { Text("Manifest") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(m) } },
                        confirmButton = { TextButton(onClick = { vm.manifest = null }) { Text("OK") } }
                    )
                }
            }
        }
    }
}