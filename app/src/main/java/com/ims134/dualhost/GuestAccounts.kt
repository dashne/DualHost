package com.ims134.dualhost

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context

object GuestAccounts {
    const val TYPE = "com.ims134.dualhost.viber"
    const val AUTHORITY_CONTACTS = "com.android.contacts"
    const val FALLBACK_NAME = "Me"

    /** Set this the moment registration completes (E.164 phone number). ADAPT: wire from
     *  the same place you log EPROBE init=true / "connected -> GuestSync.run". */
    @Volatile var registeredName: String? = null

    @Volatile private var realAccountReady = false

    fun currentAccount(): Account = Account(registeredName ?: FALLBACK_NAME, TYPE)

    fun realAccountReady(): Boolean = realAccountReady

    /** True when a local (fake) reply is still needed. */
    fun needsFakeReply(): Boolean = !realAccountReady

    /**
     * Creates the account in the REAL AccountManagerService. Works only if DualHost's
     * manifest declares the authenticator for TYPE (see manifest additions). Idempotent.
     */
    fun ensureRealAccount(appContext: Context): Boolean {
        if (realAccountReady) return true
        val acc = currentAccount()
        return try {
            val am = AccountManager.get(appContext)
            val exists = am.getAccountsByType(TYPE).any { it.name == acc.name }
            val ok = exists || am.addAccountExplicitly(acc, null, null, null)
            realAccountReady = ok || exists
            FileLog.d( "ACC ensureRealAccount name=${acc.name} ok=$ok exists=$exists")
            realAccountReady
        } catch (t: Throwable) {
            // IllegalStateException here = authenticator for TYPE not declared in manifest
            FileLog.d( "ACC ensureRealAccount FAILED: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }
}