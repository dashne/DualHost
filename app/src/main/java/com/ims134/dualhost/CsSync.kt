package com.ims134.dualhost

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.util.Log

object CsSync {
    private const val TAG = "DualHost"
    private const val AUTHORITY = "com.android.contacts"
    private const val ACCOUNT_TYPE = "com.ims134.dualhost.viber"

    fun armAfterRegistration(context: Context) {
        try {
            val am = AccountManager.get(context)
            val targets = am.getAccountsByType(ACCOUNT_TYPE)
            if (targets.isEmpty()) {
                Log.d(TAG, "CS armAfterRegistration: NO account of type $ACCOUNT_TYPE found - nothing armed")
                try {
                    val others = am.accounts
                        .filter { it.type.contains("viber") || it.type.contains("dualhost") }
                        .joinToString { "${it.name}|${it.type}" }
                    Log.d(TAG, "CS diagnostic viber/dualhost-ish accounts: [$others]")
                } catch (t: Throwable) {
                    Log.d(TAG, "CS diagnostic skipped: ${t.javaClass.simpleName}: ${t.message}")
                }
                return
            }
            for (acc in targets) {
                ContentResolver.setIsSyncable(acc, AUTHORITY, 1)
                ContentResolver.setSyncAutomatically(acc, AUTHORITY, true)
                ContentResolver.requestSync(acc, AUTHORITY, Bundle.EMPTY)
                Log.d(TAG, "CS armed: ${acc.name}/${acc.type} authority=$AUTHORITY isSyncable=1 syncAuto=true requestSync fired")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "CS armAfterRegistration FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}