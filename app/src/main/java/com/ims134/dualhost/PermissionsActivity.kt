package com.ims134.dualhost

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.ims134.dualhost.ui.theme.DualHostTheme

class PermissionsActivity : ComponentActivity() {

    private val vm: PermissionsViewModel by viewModels()

    private var lastAsked: List<String> = emptyList()

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packageName)))
        } catch (e: Throwable) {
            Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private val askRuntime = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        vm.refresh()
        val denied = lastAsked.filter {
            res[it] != true && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        // After "don't ask again" Android shows no dialog any more: go to the settings page
        if (denied.isNotEmpty() && denied.none {
                ActivityCompat.shouldShowRequestPermissionRationale(this, it)
            }) {
            openAppDetails()
        }
    }

    private fun openSettings(key: String) {
        val rp = RuntimePerms.find(key)
        if (rp != null) {
            val missing = rp.perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                openAppDetails()   // already allowed: let the user switch it off
            } else {
                lastAsked = missing
                askRuntime.launch(missing.toTypedArray())
            }
            return
        }
        val pkg = Uri.parse("package:" + packageName)
        val i = when (key) {
            "media" -> Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, pkg)
            "files" -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
            "notif" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            "alarm" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            else -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
        }
        try {
            startActivity(i)
        } catch (e: Throwable) {
            openAppDetails()
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            DualHostTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Permissions") },
                            navigationIcon = { TextButton(onClick = { finish() }) { Text("Back") } }
                        )
                    }
                ) { pad ->
                    LazyColumn(
                        Modifier.padding(pad).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(vm.rows, key = { it.key }) { r ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.title, style = MaterialTheme.typography.titleMedium)
                                        Text(r.note, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            if (r.granted) "On" else "Off",
                                            color = if (r.granted) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                    TextButton(onClick = { openSettings(r.key) }) { Text("Change") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}