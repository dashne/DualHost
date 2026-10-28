package com.ims134.dualhost

import android.app.Activity
import android.content.Intent
import android.os.Bundle

// An old notification points at a slot that now runs another clone: open that clone properly instead
class StaleLaunch : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        i.putExtra("open_clone", intent.getIntExtra("cloneId", -1))
        startActivity(i)
        finish()
    }
}