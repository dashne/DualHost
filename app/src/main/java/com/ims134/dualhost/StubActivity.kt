package com.ims134.dualhost

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File


class StubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val guestClass = intent.getStringExtra("guestClass")
        val vuid = intent.getIntExtra("vuid", -1)
        val apkPath = File(filesDir, "guest/0/base.apk").absolutePath
        val info = packageManager.getPackageArchiveInfo(apkPath, 0)

        // Step 11: read the guest app name from its resources
        var guestLabel: String
        try {
            val ai = info!!.applicationInfo!!
            ai.sourceDir = apkPath
            ai.publicSourceDir = apkPath
            val guestRes = packageManager.getResourcesForApplication(ai)
            guestLabel = guestRes.getString(ai.labelRes)
        } catch (e: Throwable) {
            guestLabel = "Failed: " + e
        }

        // Step 12: create the guest object
        var objectResult: String
        try {
            val optDir = File(filesDir, "guest/0/odex")
            optDir.mkdirs()
            val loader = dalvik.system.DexClassLoader(
                apkPath,
                optDir.absolutePath,
                null,
                classLoader
            )
            val cls = loader.loadClass(guestClass!!)
            val obj = cls.getDeclaredConstructor().newInstance()
            objectResult = "Created. Parent class: " + obj.javaClass.superclass?.name
        } catch (e: Throwable) {
            objectResult = "Failed: " + e
        }

        // Step 13: test the hidden API
        var hiddenResult: String
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val method = atClass.getDeclaredMethod("currentActivityThread")
            method.isAccessible = true
            val thread = method.invoke(null)
            hiddenResult = "Allowed: " + thread
        } catch (e: Throwable) {
            hiddenResult = "Blocked: " + e.javaClass.simpleName
        }

        // Step 14: test the internal fields
        var fieldResult: String
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)

            val f1 = atClass.getDeclaredField("mInstrumentation")
            f1.isAccessible = true
            val instr = f1.get(thread)

            val f2 = atClass.getDeclaredField("mH")
            f2.isAccessible = true
            val h = f2.get(thread)

            fieldResult = "mInstrumentation OK (" + instr?.javaClass?.simpleName +
                    "), mH OK (" + h?.javaClass?.simpleName + ")"
        } catch (e: Throwable) {
            fieldResult = "Blocked: " + e.javaClass.simpleName + " " + e.message
        }

        // Step 15 is now done by DualApplication at process start
        HookInstaller.install()
        val hookResult = "installed by DualApplication"

        val text = "STUB IS ALIVE" +
                "\nClone number: " + vuid +
                "\nApp to open: " + guestClass +
                "\nGuest package: " + info?.packageName +
                "\nGuest version: " + info?.versionName +
                "\nGuest app name: " + guestLabel +
                "\nGuest object: " + objectResult +
                "\nHidden API: " + hiddenResult +
                "\nInternal fields: " + fieldResult +
                "\nHook: " + hookResult
        Log.d("DualHost", text)

        // Step 16: screen with a button to launch the stub again
        val tv = TextView(this)
        tv.textSize = 20f
        tv.setPadding(48, 200, 48, 48)
        tv.setTextColor(Color.WHITE)
        tv.text = text

        val button = Button(this)
        button.text = "Launch stub again"
        button.setOnClickListener {
            startActivity(
                Intent(this, StubActivity::class.java)
                    .putExtras(intent)
                    .putExtra("apkPath", apkPath)
            )
        }

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(tv)
        layout.addView(button)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.DKGRAY)
        scroll.addView(layout)
        setContentView(scroll)
    }
}


/*

class StubActivity: Activity()
{
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("DualHost", "StubActivity onCreate started")

        val tv = TextView(this)
        tv.textSize = 22f
        tv.setPadding(48, 200, 48, 48)
        tv.setTextColor(Color.WHITE)
//        tv.text = "STUB IS ALIVE\nClone: " + intent.getIntExtra("vuid", -1) +
//                "\nApp: " + intent.getStringExtra("guestClass")

        val guestClass = intent.getStringExtra("guestClass")
        val vuid = intent.getIntExtra("vuid", -1)
        //tv.text = "I am the stub window\nClone number: " + vuid + "\nApp to open: " + guestClass

        val apkPath = File(filesDir, "guest/0/base.apk").absolutePath
        val info = packageManager.getPackageArchiveInfo(apkPath, 0)

        var guestLabel: String
        try {
            val ai = info!!.applicationInfo!!
            ai.sourceDir = apkPath
            ai.publicSourceDir = apkPath
            val guestRes = packageManager.getResourcesForApplication(ai)
            guestLabel = guestRes.getString(ai.labelRes)
        } catch (e: Throwable) {
            guestLabel = "Failed: " + e
        }

        var objectResult: String
        try {
            val optDir = File(filesDir, "guest/0/odex")
            optDir.mkdirs()
            val loader = dalvik.system.DexClassLoader(
                apkPath,
                optDir.absolutePath,
                null,
                classLoader
            )
            val cls = loader.loadClass(guestClass!!)
            val obj = cls.getDeclaredConstructor().newInstance()
            objectResult = "Created. Parent class: " + obj.javaClass.superclass?.name
        } catch (e: Throwable) {
            objectResult = "Failed: " + e
        }
        var hiddenResult: String
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val method = atClass.getDeclaredMethod("currentActivityThread")
            method.isAccessible = true
            val thread = method.invoke(null)
            hiddenResult = "Allowed: " + thread
        } catch (e: Throwable) {
            hiddenResult = "Blocked: " + e.javaClass.simpleName
        }
        // Step 14: test the internal fields
        var fieldResult: String
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)

            val f1 = atClass.getDeclaredField("mInstrumentation")
            f1.isAccessible = true
            val instr = f1.get(thread)

            val f2 = atClass.getDeclaredField("mH")
            f2.isAccessible = true
            val h = f2.get(thread)

            fieldResult = "mInstrumentation OK (" + instr?.javaClass?.simpleName +
                    "), mH OK (" + h?.javaClass?.simpleName + ")"
        } catch (e: Throwable) {
            fieldResult = "Blocked: " + e.javaClass.simpleName + " " + e.message
        }

        // Step 15: install our own Instrumentation
        var hookResult: String
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val f = atClass.getDeclaredField("mInstrumentation")
            f.isAccessible = true
            if (f.get(thread) is HostInstrumentation) {
                hookResult = "Already installed"
            } else {
                f.set(thread, HostInstrumentation())
                hookResult = "Installed: " + f.get(thread)?.javaClass?.simpleName
            }
        } catch (e: Throwable) {
            hookResult = "Failed: " + e.javaClass.simpleName + " " + e.message
        }
        tv.text = "I am the stub window" +
                "\nClone number: " + vuid +
                "\nApp to open: " + guestClass +
                "\nGuest package: " + info?.packageName +
                "\nGuest version: " + info?.versionName +
                        "\nGuest app name: " + guestLabel +
                "\nGuest object: " + objectResult +
                "\nHidden API: " + hiddenResult +
                "\nInternal fields: " + fieldResult +
                "\nHook: " + hookResult

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.GRAY)
        scroll.addView(tv)
        setContentView(scroll)
        Log.d("DualHost", "StubActivity setContentView done")

//        val tv = TextView(this)
//        tv.textSize = 24f
//        tv.setPadding(48,200,48,48)
//        tv.setTextColor(android.graphics.Color.BLACK)
//        tv.setBackgroundColor(android.graphics.Color.WHITE)
//        tv.text= buildString {
//        append("I am the Stub Window")
//    }
//        setContentView(tv)

//        setContent {
//            DualHostTheme {
//                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
//                    StubContent(
//                        name = "I am the Stub Window",
//                        modifier = Modifier.padding(innerPadding)
//
//                    )
//                }
//            }
//        }
    }
}
*/
