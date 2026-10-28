package com.ims134.dualhost

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ims134.dualhost.ui.theme.DualHostTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class OpenLinkActivity : ComponentActivity() {

    private val vm: LinkViewModel by viewModels()

    private fun open(m: LinkMatch) {
        val uri = Uri.parse(vm.link.trim())
        val live = SlotManager.slotOf(this, m.clone.id)
        val slot = live ?: SlotManager.freeSlot(this)
        if (slot == null) {
            Toast.makeText(this, "All 10 slots are busy. Close a clone first.", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(Intent.ACTION_VIEW, uri)
        i.setClass(this, Slots.stubs[slot])
        i.putExtra("guestClass", m.handler)
        i.putExtra("guestPackage", m.clone.pkg)
        i.putExtra("vuid", slot)
        i.putExtra("cloneId", m.clone.id)
        i.putExtra("cloneNumber", m.number)
        i.putExtra("apkPath", m.apk)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        SlotManager.assign(this, slot, m.clone.id)
        CloneStore.touch(this, m.clone.id)
        // Log only the host and the handler class, never the token
        Log.d("DualHost", "OpenLink: clone=" + m.clone.id + " slot=" + slot +
                " host=" + uri.host + " handler=" + m.handler)
        startActivity(i)
        finish()
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
                            title = { Text("Open link in clone") },
                            navigationIcon = { TextButton(onClick = { finish() }) { Text("Back") } }
                        )
                    }
                ) { pad ->
                    Column(
                        Modifier.padding(pad).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LaunchedEffect(vm.link) {
                            delay(400.milliseconds)
                            if (vm.link.isNotBlank()) vm.search()
                        }
                        OutlinedTextField(
                            value = vm.link,
                            onValueChange = { vm.link = it },
                            label = { Text("Link") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                            trailingIcon = {
                                TextButton(onClick = {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.primaryClip?.getItemAt(0)?.text?.toString()?.let { vm.link = it }
                                }) { Text("Paste") }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
//                        Button(onClick = { vm.search() }, modifier = Modifier.fillMaxWidth()) {
//                            Text("Find clones")
//                        }
                        if (vm.checked && vm.link.isNotBlank() && vm.matches.isEmpty()) {
                            Text(
                                "No clone has an app that handles this link. The link needs a scheme such as https://.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(vm.matches, key = { it.clone.id }) { m ->
                                Card(onClick = { open(m) }, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (m.icon != null) Image(m.icon, null, Modifier.size(44.dp))
                                        else Box(Modifier.size(44.dp))
                                        Column(Modifier.padding(start = 16.dp)) {
                                            Text(m.clone.label + " (clone " + m.number + ")",
                                                style = MaterialTheme.typography.titleMedium)
                                            Text(m.clone.pkg, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}