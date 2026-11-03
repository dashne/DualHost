package com.ims134.dualhost

import android.accounts.Account
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

// Added 2026-11-01 (Contacts "Allow access"/"Contacts syncing incomplete" investigation): a
// side-by-side comparison against a competing app-cloning app proved this was never a
// permission problem — Viber's own in-process checkSelfPermission/checkPermission for
// READ_CONTACTS/WRITE_CONTACTS already returns GRANTED (confirmed via CloneContext's logPerm),
// getPackageInfo and AppOps both agree, and even opening DualHost's own (correctly redirected,
// see HostInstrumentation.fixSettingsIntent) Settings page and re-ticking Contacts/Call there
// changed nothing. Viber declares com.viber.service.contacts.authentication.AccountAuthenticatorService
// in its manifest (confirmed: "PM ignored setComponentEnabledSetting for guest
// com.viber.service.contacts.authentication.AccountAuthenticatorService") — the standard Android
// mechanism an app uses to register itself as an Account so it can sync contacts. A clone has no
// real, separately-installed package identity, so the real, device-wide AccountManagerService can
// never create or discover a working Account for it: getAccountsByType() for Viber's own account
// type always comes back empty for this process, same as for any app that was never actually
// installed. This fabricates one Account of Viber's own account type so Viber's own code sees a
// working account instead of none. The account type is discovered dynamically from Viber's own
// manifest (the android.accounts.AccountAuthenticator meta-data resource, read the same way
// Android itself discovers it — NOT a hardcoded guess), so this generalizes to any guest with the
// same problem.
//
// 2026-11-01 rewrite: the first version of this file reflected into AccountManager's private
// instance field "mService" and swapped it for a java.lang.reflect.Proxy. On-device logs proved
// that is a dead end on this device, not a guess:
//   hiddenapi: Accessing hidden field Landroid/accounts/AccountManager;->mService:... (domain=
//   platform, api=max-target-o) from Lcom/ims134/dualhost/AccProxy; (domain=app,
//   TargetSdkVersion=37) using reflection: denied
// mService is blocklisted for any app whose targetSdkVersion is newer than O, which this app's
// is. The usual escape hatch for that — DualApplication's dalvik.system.VMRuntime.
// setHiddenApiExemptions(["L"]) call — has itself failed with NoSuchMethodException in literally
// every single logcat capture taken since that call was first added, long before Viber's
// Contacts tab was ever investigated (checked: 90e5569a, 1b9923a1, 80cc0fd3, cf6d617f, 61eca0dd,
// 4f0deac6, c51ff43d, 276fbc0e, f87bdfd6, 02fcfa82 — all of them). That means setHiddenApiExemptions
// does not exist at all on this build of the runtime (not merely "denied" — the method itself is
// gone), so there is no remaining way, in pure Java reflection, to read AccountManager.mService
// on this device. That part of the old design is confirmed dead, full stop.
//
// This version never touches AccountManager or its hidden field. Instead it replaces the
// "account" entry in android.os.ServiceManager's own in-process service cache (sCache) with a
// local android.os.Binder subclass that passes every call straight through to the real
// AccountManagerService except the few read calls (getAccounts/getAccountsByType/...) that ask
// about Viber's own account type, which it answers locally with the fabricated Account. Because
// AccountManager itself resolves "account" through ServiceManager.getService() the first time any
// instance needs it — exactly like every other caller in the process, framework code included —
// this patches every future AccountManager instance automatically; AccountManager's mService
// field is never read or written by this code. android.os.ServiceManager.getService/addService
// and android.os.Binder are both ordinary public SDK API (ServiceManager has been public since
// API 28), so none of this needs a hidden-API bypass, and it only ever changes this process's own
// local cache — it never calls the real, privileged ServiceManager.addService() IPC, which a
// normal app has no permission to do anyway.
//
// 2026-11-03: interceptCodes (read off the blocklisted IAccountManager$Stub.TRANSACTION_*
// constants) resolves EMPTY on this device, same hidden-API wall as mService above (log:
// "Accessing hidden field Landroid/accounts/IAccountManager$Stub;->TRANSACTION_getAccountsBy
// TypeForPackage:I ... denied"). The original fallback plan was to manually read the real
// transaction-code number off an "ACC OBSERVE" log line and hardcode it into manuallyKnownCodes
// below. On-device logs show why that plan doesn't work as written: the old diagnostic only ever
// logged a given code the FIRST time it was ever seen (observed.add(code) in the old version), so
// if Viber's own getAccountsByType("com.viber.voip") call happened to reuse a code number already
// logged once for something unrelated (e.g. a "com.google" call), it passed through completely
// silently and never appeared in the log at all — confirmed: a real capture shows "ACC discovered
// accountType=com.viber.voip" followed later by ACC OBSERVE lines for codes 6, 46 and 10, none of
// them with firstArg=com.viber.voip, which is not proof that call didn't happen, just proof the
// old one-shot-per-code gate hid it.
//
// So this version doesn't try to learn or hardcode a single fixed number at all. Instead it peeks
// the first argument of every not-yet-classified IAccountManager call, on every single
// invocation (not just the first time a given code is seen), and the moment any call's first
// argument equals Viber's own discovered account type, it fakes that exact call's reply AND
// remembers the code so every future call on that same code is intercepted immediately without
// needing to peek again. This is robust across devices/API levels/OEM AIDL reorderings because it
// identifies the call by what it's asking about, not by a number nobody can reliably read.
object AccProxy {
    private val seen = HashSet<String>()
    private fun logOnce(s: String) { if (synchronized(seen) { seen.add(s) }) FileLog.d(s) }

    @Volatile var accountType: String? = null
    @Volatile var fakeAccount: Account? = null
    @Volatile private var patchedOnce = false

    // Reads the SAME meta-data Android itself uses to discover an authenticator's account type
    // (res/xml/authenticator.xml, root <account-authenticator android:accountType="..."/>) —
    // not a hardcoded guess, so this adapts to whatever the guest app actually declares.
    //
    // Fixed 2026-11-01: the previous version read the raw attribute value with
    // parser.getAttributeValue(i), which for an accountType declared as a resource reference
    // (the common case — Viber's own authenticator.xml points accountType at a @string/... entry,
    // not a literal) returns the UNRESOLVED reference string ("@2132017152"), not the actual
    // account-type string. Confirmed on-device: "ACC discovered accountType=@2132017152". Now
    // checks getAttributeResourceValue() first and, when the attribute really is a reference,
    // resolves it through the guest's own Resources — falling back to the literal value only when
    // it genuinely isn't a reference.
    private fun discoverAccountType(apkPath: String): String? {
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as android.app.Application
            val pm = app.packageManager
            val info = pm.getPackageArchiveInfo(
                apkPath, PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
            ) ?: return null
            val authSvc = info.services?.firstOrNull {
                it.metaData?.containsKey("android.accounts.AccountAuthenticator") == true
            } ?: run {
                logOnce("ACC no AccountAuthenticator service declared in guest manifest")
                return null
            }
            val resId = authSvc.metaData.getInt("android.accounts.AccountAuthenticator")
            if (resId == 0) return null
            val ai = info.applicationInfo ?: return null
            ai.sourceDir = apkPath
            ai.publicSourceDir = apkPath
            val res = pm.getResourcesForApplication(ai)
            val parser = res.getXml(resId)
            var ev = parser.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "account-authenticator") {
                    for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeName(i) == "accountType") {
                            val valueResId = parser.getAttributeResourceValue(i, 0)
                            val t = if (valueResId != 0) {
                                try {
                                    res.getString(valueResId)
                                } catch (e: Throwable) {
                                    logOnce("ACC accountType resId=" + valueResId + " failed to resolve: " + e)
                                    null
                                }
                            } else {
                                parser.getAttributeValue(i)
                            }
                            if (t != null) {
                                logOnce("ACC discovered accountType=" + t)
                                return t
                            }
                        }
                    }
                }
                ev = parser.next()
            }
            logOnce("ACC authenticator xml had no (resolvable) accountType attribute")
            null
        } catch (e: Throwable) {
            logOnce("ACC discoverAccountType failed: " + e)
            null
        }
    }

    // A local Binder standing in for the real "account" service. Anything that isn't one of the
    // read calls we care about is forwarded untouched to the real binder via transact(), so every
    // other AccountManager feature (Google accounts, Drive sign-in, etc.) keeps working exactly
    // as before — this only ever answers on Viber's own account type.
    private class PassthroughBinder(
        private val real: IBinder,
        private val type: String,
        private val account: Account
    ) : Binder() {
        private val fakeAccounts = arrayOf(account)

        private val interceptCodes: Set<Int> by lazy {
            val s = HashSet<Int>()
            try {
                val stub = Class.forName("android.accounts.IAccountManager\$Stub")
                for (name in listOf(
                    "TRANSACTION_getAccounts",
                    "TRANSACTION_getAccountsByType",
                    "TRANSACTION_getAccountsByTypeForPackage",
                    "TRANSACTION_getAccountsAsUser",
                    "TRANSACTION_getAccountsByTypeAsUser"
                )) {
                    try {
                        val f = stub.getDeclaredField(name)
                        f.isAccessible = true
                        s.add(f.getInt(null))
                    } catch (e: Throwable) {
                        // Not every transaction exists on every API level — fine, skip it.
                    }
                }
                logOnce("ACC PassthroughBinder resolved " + s.size + " transaction code(s)")
            } catch (e: Throwable) {
                logOnce("ACC PassthroughBinder: could not resolve IAccountManager.Stub: " + e)
            }
            s
        }

        // 2026-11-03: codes this process has confirmed, BY OBSERVED BEHAVIOR (a call whose first
        // argument equaled our own discovered account type), actually carry an account-type
        // query. Populated live by onTransact below — never hardcoded, never dependent on reading
        // the blocklisted TRANSACTION_* constants. Once a code lands here, every future call on
        // that code is answered with the fake account immediately, with no need to re-peek it.
        private val learnedCodes = java.util.Collections.synchronizedSet(HashSet<Int>())

        // Diagnostic-only: codes we've logged at least once via "ACC OBSERVE", purely so the log
        // doesn't repeat the same unrelated call forever. This NEVER gates whether a call gets
        // checked against our account type — every single call is checked, every time, regardless
        // of whether its code has been seen before. (The previous version gated the check itself
        // on this set, which is what silently hid the real call in the 11-02 12:24 capture.)
        private val loggedOnce = java.util.Collections.synchronizedSet(HashSet<Int>())

        private fun answerFake(reply: Parcel) {
            reply.writeNoException()
            reply.writeTypedArray(fakeAccounts, 0)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (reply != null && (code in interceptCodes || code in learnedCodes)) {
                val mark = data.dataPosition()
                try {
                    data.enforceInterface("android.accounts.IAccountManager")
                    // The account-type string is the first argument on every one of these calls
                    // across every API level this interface has shipped with — read it, but don't
                    // trust it blindly: on any read failure or type mismatch, rewind and pass the
                    // call through to the real service instead of risking handing back a fake
                    // account for a type we were never asked about (Google/Drive accounts, etc.).
                    val askedType = try { data.readString() } catch (e: Throwable) { null }
                    if (askedType == null || askedType == type) {
                        answerFake(reply)
                        logOnce("ACC intercepted tx=" + code + " type=" + askedType + " -> fake account")
                        return true
                    }
                } catch (e: Throwable) {
                    logOnce("ACC intercept failed for tx=" + code + ", passing through: " + e)
                }
                data.setDataPosition(mark)
            } else if (reply != null) {
                // Peek every not-yet-learned call's first argument, on EVERY invocation (not just
                // the first time this code is seen), and compare it against our own account type.
                // The moment one matches, that's the real getAccountsByType-family call — fake its
                // reply right now and remember the code so future calls skip straight to the fast
                // path above. Anything else gets a one-time diagnostic log per code, same as
                // before, purely for visibility — it never gates the match check itself.
                val mark = data.dataPosition()
                var askedType: String? = null
                var isAccountManagerCall = true
                try {
                    data.enforceInterface("android.accounts.IAccountManager")
                    askedType = try { data.readString() } catch (e: Throwable) { null }
                } catch (e: Throwable) {
                    isAccountManagerCall = false
                } finally {
                    data.setDataPosition(mark)
                }
                if (isAccountManagerCall && askedType == type) {
                    learnedCodes.add(code)
                    logOnce("ACC AUTO-LEARNED tx=" + code + " (firstArg matched accountType=" + type + ") -> fake account from now on")
                    val mark2 = data.dataPosition()
                    try {
                        data.enforceInterface("android.accounts.IAccountManager")
                        data.readString()
                        answerFake(reply)
                        return true
                    } catch (e: Throwable) {
                        logOnce("ACC auto-learned intercept failed for tx=" + code + ", passing through: " + e)
                        data.setDataPosition(mark2)
                    }
                } else if (isAccountManagerCall && loggedOnce.add(code)) {
                    logOnce("ACC OBSERVE tx=" + code + " firstArg=" + askedType)
                }
            }
            return real.transact(code, data, reply, flags)
        }
    }

    // Called once per guest (from HookInstaller, same as every other *Proxy.install()) with the
    // guest's own apk path. Finds the real "account" binder via the (public, non-hidden)
    // ServiceManager.getService(), wraps it, and writes the wrapper back into ServiceManager's own
    // in-process cache so every future lookup — AccountManager's own included — picks it up.
    fun patch(apkPath: String) {
        try {
            if (patchedOnce) return
            val type = accountType ?: discoverAccountType(apkPath) ?: run {
                FileLog.d("AccProxy: no account type discovered, skipping")
                return
            }
            accountType = type
            val account = fakeAccount ?: Account("Me", type).also { fakeAccount = it }

            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val realBinder = getService.invoke(null, "account") as? IBinder ?: run {
                FileLog.d("AccProxy: ServiceManager has no \"account\" service yet")
                return
            }
            if (realBinder is PassthroughBinder) { patchedOnce = true; return }

            val fake = PassthroughBinder(realBinder, type, account)

            // ServiceManager keeps its own local, per-process cache (sCache) that getService()
            // checks before ever touching the binder driver — this writes straight into that
            // cache, not into the real, system-wide service table, so it only ever affects lookups
            // made from inside this process.
            val cacheF = smClass.getDeclaredField("sCache")
            cacheF.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cache = cacheF.get(null) as MutableMap<String, IBinder>
            synchronized(cache) { cache["account"] = fake }

            patchedOnce = true
            FileLog.d("AccProxy installed ServiceManager passthrough for \"account\" (accountType=" + type + ")")
        } catch (e: Throwable) {
            FileLog.d("AccProxy patch failed: " + e)
        }
    }
}