package com.ims134.dualhost

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel

class PermRow(val key: String, val title: String, val note: String, val granted: Boolean)

// A permission the user must allow by hand. anyOf = one granted permission is enough.
class RuntimePerm(
    val key: String, val title: String, val note: String,
    val minSdk: Int, val perms: List<String>, val anyOf: Boolean = false
)

object RuntimePerms {
    val all = listOf(
        RuntimePerm("camera", "Camera",
            "Needed so clones can take photos. Without it the camera app refuses to open.",
            1, listOf(Manifest.permission.CAMERA)),
        RuntimePerm("mic", "Microphone",
            "Voice messages, recorders and calls in clones.",
            1, listOf(Manifest.permission.RECORD_AUDIO)),
        RuntimePerm("contacts", "Contacts",
            "Needed by messaging apps (e.g. Viber) to find who's already on the app and let you message people from your contact list.",
            1, listOf(Manifest.permission.READ_CONTACTS)),
        RuntimePerm("phone", "Phone",
            "Needed by messaging apps (e.g. Viber) to read SIM and phone state for activation and service status.",
            1, listOf(Manifest.permission.READ_PHONE_STATE)),
        RuntimePerm("photos", "Photos and videos",
            "Lets clones pick and show your pictures and videos.",
            33, listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)),
        RuntimePerm("audio", "Music and audio files",
            "Music players and recorders reading audio files.",
            33, listOf(Manifest.permission.READ_MEDIA_AUDIO)),
        RuntimePerm("location", "Location",
            "Maps, weather and nearby search in clones.",
            1, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            anyOf = true),
        RuntimePerm("nearby", "Bluetooth devices",
            "Headphones and other nearby devices.",
            31, listOf(Manifest.permission.BLUETOOTH_CONNECT))
    )

    fun find(key: String): RuntimePerm? = all.firstOrNull { it.key == key }
}

class PermissionsViewModel(app: Application) : AndroidViewModel(app) {

    var rows by mutableStateOf<List<PermRow>>(emptyList())
        private set

    fun refresh() {
        val c = getApplication<Application>()
        val nm = c.getSystemService(NotificationManager::class.java)
        val list = ArrayList<PermRow>()

        for (p in RuntimePerms.all) {
            if (Build.VERSION.SDK_INT < p.minSdk) continue
            val ok = if (p.anyOf)
                p.perms.any { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            else
                p.perms.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            list.add(PermRow(p.key, p.title, p.note, ok))
        }

        list.add(PermRow("notif", "Notifications",
            "Alarms, music controls and messages from clones.",
            nm.areNotificationsEnabled()))
        if (Build.VERSION.SDK_INT >= 31)
        {
            list.add(PermRow("media", "Media management",
                "Needed by gallery apps. Stops them asking for all-files access.",
                MediaStore.canManageMedia(c)))
        }


        val permission = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else {
            ContextCompat.checkSelfPermission(c,Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(c,Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        list.add(PermRow("files", "All files access (keep off)",
            "Leave this off. Gallery, Camera and Voice Recorder work without it. With it on, Camera shows an error toast and Voice Recorder cannot save.",
            permission))


//        list.add(PermRow("files", "All files access (keep off)",
//                        "Leave this off. Gallery, Camera and Voice Recorder work without it. With it on, Camera shows an error toast and Voice Recorder cannot save.",
//            Environment.isExternalStorageManager()))
        if (Build.VERSION.SDK_INT >= 31)
        {
            val am = c.getSystemService(AlarmManager::class.java)
            list.add(PermRow("alarm", "Exact alarms",
                "Needed for clock apps to ring on time.", am.canScheduleExactAlarms()))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            list.add(PermRow("fullscreen", "Full-screen alarms",
                "Lets an alarm take over a locked screen.", nm.canUseFullScreenIntent()))
        }
        rows = list
    }
}