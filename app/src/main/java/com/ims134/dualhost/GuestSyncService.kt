package com.ims134.dualhost

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.util.Log

class GuestSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder = GuestSyncAdapter(this).syncAdapterBinder
}

private class GuestSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String,
                               provider: ContentProviderClient, syncResult: SyncResult) {
        Log.d("DualHost", "GSYNC framework sync: account=${account.name} authority=$authority extras=$extras")
        // ADAPT (one line): delegate to your existing engine, e.g.
        // GuestSyncRunner.run(account, authority, extras)
    }
}