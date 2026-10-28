package com.ims134.dualhost

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.io.File

// Receives a guest's sign-in return link and passes it to the clone that started the sign-in
class ReturnLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val uri = intent.data
            val prefs = getSharedPreferences("dualhost", MODE_PRIVATE)
            val slot = prefs.getInt("ret_slot", -1)
            val apk = prefs.getString("ret_apk", null)
            if (uri != null && slot in Slots.stubs.indices && apk != null && File(apk).exists()) {
                val pkg = File(apk).parentFile?.parentFile?.name ?: ""
                val handler = OpenLinkHandlers.find(this, apk, uri)
                Log.d("DualHost", "ReturnLink: scheme=" + uri.scheme + " slot=" + slot + " handler=" + handler)
                if (handler != null) {
                    val i = Intent(Intent.ACTION_VIEW, uri)
                    i.setClass(this, Slots.stubs[slot])
                    i.putExtra("guestClass", handler)
                    i.putExtra("guestPackage", pkg)
                    i.putExtra("vuid", slot)
                    i.putExtra("apkPath", apk)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                }
            } else {
                Log.d("DualHost", "ReturnLink: nothing to forward, slot=$slot")
            }
        } catch (e: Throwable) {
            Log.d("DualHost", "ReturnLink failed: $e")
        }
        finish()
    }
}