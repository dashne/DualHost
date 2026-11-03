package com.ims134.dualhost

import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

// Added 2026-10-31: the PmProxy.checkPermission/checkUidPermission fix (added earlier today for
// the Viber Contacts "Allow access" loop) patched IPackageManager, but on Android versions where
// the permission subsystem was split out (checkPermission(permName, pkgName[, userId]) moved off
// IPackageManager onto a SEPARATE AIDL service, IPermissionManager, reached via
// android.permission.PermissionManager rather than ActivityThread.sPackageManager), that
// interception never fires for this call at all — which is consistent with the fix having no
// effect on device. This patches IPermissionManager the same way, but searches for the field
// holding it BY TYPE rather than a guessed name: the exact field name, and whether
// ActivityThread or the per-Context PermissionManager object owns the cached reference, differ
// across API levels and this project has no way to inspect this exact ROM's framework.jar.
// Degrades gracefully (logs and leaves real behavior in place) if no matching field is found —
// e.g. on an OS where checkPermission genuinely is still on IPackageManager and PmProxy's own
// interception already covers it, in which case this file simply never finds anything to patch
// and does nothing, same tolerant style as every other proxy in this project.
object PermProxy {
    private const val HOST = "com.ims134.dualhost"
    private val seen = HashSet<String>()
    private fun logOnce(s: String) { if (synchronized(seen) { seen.add(s) }) FileLog.d(s) }

    @Volatile private var proxy: Any? = null

    private fun swapField(owner: Any?, declaringClass: Class<*>, value: Any): Boolean {
        for (f in declaringClass.declaredFields) {
            if (f.type.isInterface && f.type.simpleName == "IPermissionManager") {
                try {
                    f.isAccessible = true
                    f.set(owner, value)
                    return true
                } catch (e: Throwable) {
                    logOnce("PermProxy: field " + f.name + " swap failed: " + e)
                }
            }
        }
        return false
    }

    private fun makeProxy(iface: Class<*>, realObj: Any): Any {
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
            if ((m.name == "checkPermission" || m.name == "checkUidPermission") &&
                args != null && args.size >= 2 && args[0] is String && args[1] is String) {
                val permName = args[0] as String
                val askedPkg = args[1] as String
                val gp = PmProxy.guestApk?.let { java.io.File(it).parentFile?.parentFile?.name }
                if (gp != null && askedPkg == gp) {
                    try {
                        val fixedArgs = args.copyOf()
                        fixedArgs[1] = HOST
                        var r = (m.invoke(realObj, *fixedArgs) as? Int) ?: PackageManager.PERMISSION_DENIED
                        if (r == PackageManager.PERMISSION_GRANTED && permName in ClonePerms.GATED) {
                            val app = Class.forName("android.app.ActivityThread")
                                .getDeclaredMethod("currentApplication").invoke(null) as android.app.Application
                            val cloneId = ProcessClone.id.takeIf { it >= 0 } ?: ProcessClone.slot
                            if (cloneId != null && !ClonePerms.isAllowed(app, gp, cloneId, permName)) {
                                r = PackageManager.PERMISSION_DENIED
                            }
                        }
                        logOnce("PERM " + m.name + " redirected pkg=" + askedPkg + " -> " + HOST +
                                " perm=" + permName + " result=" + r)
                        return@newProxyInstance r
                    } catch (e: Throwable) {
                        logOnce("PERM " + m.name + " redirect failed: " + e)
                    }
                }
            }
            try {
                if (args == null) m.invoke(realObj) else m.invoke(realObj, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    fun install() {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            // Force resolution first (mirrors CsProxy's sContentService handling): the cached
            // field may still be null the first time this process touches it.
            try {
                val cands = listOf("getPermissionManager")
                for (name in cands) {
                    try {
                        val m = atClass.getDeclaredMethod(name)
                        m.isAccessible = true
                        m.invoke(null)
                        break
                    } catch (e: NoSuchMethodException) { /* try next / give up */ }
                }
            } catch (e: Throwable) { }

            var found = false
            for (f in atClass.declaredFields) {
                if (f.type.isInterface && f.type.simpleName == "IPermissionManager") {
                    f.isAccessible = true
                    val cur = f.get(null)
                    if (cur != null) {
                        val p = makeProxy(f.type, cur)
                        proxy = p
                        f.set(null, p)
                        found = true
                        FileLog.d("PermProxy installed via ActivityThread." + f.name)
                        break
                    }
                }
            }
            if (!found) {
                FileLog.d("PermProxy: no non-null IPermissionManager field found on ActivityThread " +
                        "(either this API level keeps checkPermission on IPackageManager, in which " +
                        "case PmProxy's own interception already covers it, or the field isn't " +
                        "resolved yet in this process)")
            }
        } catch (e: Throwable) {
            FileLog.d("PermProxy failed: " + e)
        }
    }

    // A PermissionManager (or PackageManager, on some versions) object created before install()
    // still holds the real IPermissionManager reference — mirrors PmProxy.patch(pm).
    fun patch(ctx: Context) {
        val p = proxy ?: return
        try {
            val svc = ctx.getSystemService("permission") ?: return
            if (swapField(svc, svc.javaClass, p)) {
                logOnce("PermProxy: patched existing PermissionManager instance")
            }
        } catch (e: Throwable) {
            logOnce("PermProxy patch failed: " + e)
        }
    }
}