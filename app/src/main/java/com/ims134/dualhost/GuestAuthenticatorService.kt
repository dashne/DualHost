package com.ims134.dualhost




import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

class GuestAuthenticatorService : Service() {
    private val authenticator by lazy { GuestAuthenticator(this) }
    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}

private class GuestAuthenticator(ctx: Context) : AbstractAccountAuthenticator(ctx) {
    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
    override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?,
                            authTokenType: String?, requiredFeatures: Array<out String>?,
                            options: Bundle?): Bundle? = null
    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?,
                                    options: Bundle?): Bundle? = null
    override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?,
                              authTokenType: String?, options: Bundle?): Bundle? = null
    override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?,
                                   authTokenType: String?, options: Bundle?): Bundle? = null
    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?,
                             features: Array<out String>?): Bundle? = null
    override fun getAuthTokenLabel(authTokenType: String?): String? = null
}