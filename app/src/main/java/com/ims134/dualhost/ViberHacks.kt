package com.ims134.dualhost

import android.app.Application

object ViberHacks {

    fun setFactory(loader: ClassLoader, app: Application) {
        scanProcessName()

        // Attempt 1: bypass spf0, call hsv.d directly + ViberEnv.init + gmv0.j()
        val direct = tryDirectHsv(loader)
        FileLog.d("SPF0 direct outcome=" + direct)

        // Attempt 2: fall back to spf0.a if direct failed
        val outcome = if (direct == "unit" || direct == "suspended") direct
        else trySpf0Real(loader, app)
        FileLog.d("SPF0 outcome=" + outcome)

        val after = readFactory(loader)
        FileLog.d("VFACTORY after spf0: sViberFactory = " + (after?.javaClass?.name ?: "null"))

        if (after == null && outcome == "error") {
            FileLog.d("VFACTORY fake fallback")
            tryFake(loader)
            tryGmv0j(loader)
            val final = readFactory(loader)
            FileLog.d("VFACTORY final sViberFactory = " + (final?.javaClass?.name ?: "null"))
        }
    }

    // ---- Attempt 1: hsv.d directly → ViberEnv.init(d(z3x)) → gmv0.j() ----
    private fun tryDirectHsv(loader: ClassLoader): String {
        return try {
            val x3xCls = loader.loadClass("x3x")
            val rpf0 = buildValidContinuation(loader) ?: return "error"

            val hsvCls = loader.loadClass("hsv")
            val hsvD = hsvCls.declaredMethods.first { it.name == "d" && it.parameterCount == 3 }
            hsvD.isAccessible = true

            for (withX3x in listOf(false, true)) {
                val hs = java.util.HashSet<Any>()
                if (withX3x) hs.add(x3xCls)
                val tag = if (withX3x) "with-x3x" else "empty"

                val result = try {
                    hsvD.invoke(null, hs, x3xCls, rpf0)
                } catch (e: Throwable) {
                    FileLog.d("DIRECT hsv.d[$tag] threw: " + (e.cause ?: e))
                    continue
                }
                val cls = result?.javaClass?.name ?: "null"
                FileLog.d("DIRECT hsv.d[$tag] = $cls")
                if (result == null) continue
                if (cls.contains("CoroutineSingletons")) {
                    FileLog.d("DIRECT hsv.d[$tag] SUSPENDED")
                    continue
                }

                // We have a z3x. Do ViberEnv.init(new d(z3x)) then gmv0.j()
                try {
                    val envCls = loader.loadClass("com.viber.voip.ViberEnv")
                    val facCls = loader.loadClass("com.viber.voip.ViberFactory")
                    val dCls = loader.loadClass("com.viber.voip.d")
                    val dCtor = dCls.getDeclaredConstructor(loader.loadClass("z3x"))
                        .apply { isAccessible = true }
                    val factory = dCtor.newInstance(result)
                    envCls.getMethod("init", facCls).invoke(null, factory)
                    FileLog.d("DIRECT ViberEnv.init done with " + factory.javaClass.name)
                } catch (e: Throwable) {
                    FileLog.d("DIRECT ViberEnv.init failed: " + (e.cause ?: e))
                    continue
                }

                try {
                    val gmv0Cls = loader.loadClass("gmv0")
                    val j = gmv0Cls.declaredMethods.first { it.name == "j" && it.parameterCount == 0 }
                    j.isAccessible = true
                    FileLog.d("DIRECT gmv0.j() = " + j.invoke(null))
                } catch (e: Throwable) {
                    FileLog.d("DIRECT gmv0.j() failed: " + (e.cause ?: e))
                }
                return "unit"
            }
            "error"
        } catch (e: Throwable) {
            FileLog.d("DIRECT failed: " + (e.cause ?: e))
            "error"
        }
    }

    // Build rpf0 with its CoroutineContext field set so getContext() doesn't NPE.
    private fun buildValidContinuation(loader: ClassLoader): Any? {
        return try {
            val spf0Cls = loader.loadClass("spf0")
            val spf0Inst = spf0Cls.getDeclaredConstructor().newInstance()
            val rpf0Cls = loader.loadClass("rpf0")
            val contImplCls = loader.loadClass("kotlin.coroutines.jvm.internal.ContinuationImpl")

            val ctor = rpf0Cls.declaredConstructors.first()
            ctor.isAccessible = true
            val args = ctor.parameterTypes.map {
                if (it.name == "spf0") spf0Inst else null
            }
            val inst = ctor.newInstance(*args.toTypedArray())
            FileLog.d("CONT built rpf0(" + ctor.parameterTypes.joinToString(",") { it.simpleName } + ")")

            val emptyCtxCls = loader.loadClass("kotlin.coroutines.EmptyCoroutineContext")
            val emptyCtx = emptyCtxCls.getField("INSTANCE").get(null)

            var setCount = 0
            var k: Class<*>? = rpf0Cls
            while (k != null) {
                for (f in k.declaredFields) {
                    if (f.type.name == "kotlin.coroutines.CoroutineContext" &&
                        !java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                        try {
                            f.isAccessible = true
                            f.set(inst, emptyCtx)
                            FileLog.d("CONT set " + k.name + "." + f.name + " = EmptyCoroutineContext")
                            setCount++
                        } catch (t: Throwable) {
                            FileLog.d("CONT set " + f.name + " failed: " + t)
                        }
                    }
                }
                k = k.superclass
            }

            // Verify getContext works now
            try {
                val getCtx = contImplCls.getMethod("getContext")
                getCtx.isAccessible = true
                val ctx = getCtx.invoke(inst)
                FileLog.d("CONT getContext() = " + (ctx?.javaClass?.name ?: "null"))
            } catch (t: Throwable) {
                FileLog.d("CONT getContext() throws: " + (t.cause ?: t))
            }

            if (setCount > 0) inst else null
        } catch (e: Throwable) {
            FileLog.d("CONT build failed: " + (e.cause ?: e))
            null
        }
    }

    // ---- Attempt 2: spf0.a with the fixed rpf0 ----
    private fun trySpf0Real(loader: ClassLoader, app: Application): String {
        return try {
            val spf0Cls = loader.loadClass("spf0")
            val spf0Inst = spf0Cls.getDeclaredConstructor().newInstance()
            val contCls = loader.loadClass("kotlin.coroutines.Continuation")
            val spf0M = spf0Cls.getDeclaredMethod(
                "a", android.content.Context::class.java,
                java.util.HashSet::class.java, contCls)
            spf0M.isAccessible = true

            val rpf0 = buildValidContinuation(loader) ?: return "error"
            val hs = java.util.HashSet<Any>()

            try {
                val r = spf0M.invoke(spf0Inst, app, hs, rpf0)
                val cls = r?.javaClass?.name ?: "null"
                FileLog.d("SPF0 returned " + cls)
                return when {
                    cls == "kotlin.Unit" -> "unit"
                    cls.contains("CoroutineSingletons") -> "suspended"
                    else -> "other:$cls"
                }
            } catch (e: Throwable) {
                FileLog.d("SPF0 failed: " + (e.cause ?: e))
                FileLog.d("SPF0 stack: " +
                        android.util.Log.getStackTraceString(e.cause ?: e).take(2000))
                "error"
            }
        } catch (e: Throwable) {
            FileLog.d("SPF0 outer failed: " + (e.cause ?: e))
            "error"
        }
    }

    private fun tryFake(loader: ClassLoader) {
        try {
            val envCls = loader.loadClass("com.viber.voip.ViberEnv")
            val facCls = loader.loadClass("com.viber.voip.ViberFactory")
            val z3xCls = loader.loadClass("z3x")
            val z3xACls = loader.loadClass("z3x\$a")
            val ctor = z3xCls.declaredConstructors.first {
                it.parameterCount == 2 && it.parameterTypes[0] == z3xACls
            }
            ctor.isAccessible = true
            val aCtor = z3xACls.getDeclaredConstructor().apply { isAccessible = true }
            val cfg = ctor.newInstance(aCtor.newInstance(), null)
            val dCls = loader.loadClass("com.viber.voip.d")
            val dCtor = dCls.getDeclaredConstructor(z3xCls).apply { isAccessible = true }
            val factory = dCtor.newInstance(cfg)
            envCls.getMethod("init", facCls).invoke(null, factory)
            FileLog.d("VFACTORY fake ViberEnv.init called with " + factory.javaClass.name)
        } catch (e: Throwable) {
            FileLog.d("VFACTORY fake failed: " + (e.cause ?: e))
        }
    }

    private fun tryGmv0j(loader: ClassLoader) {
        try {
            val gmv0Cls = loader.loadClass("gmv0")
            val j = gmv0Cls.declaredMethods.first { it.name == "j" && it.parameterCount == 0 }
            j.isAccessible = true
            FileLog.d("VFACTORY gmv0.j() returned: " + j.invoke(null))
        } catch (e: Throwable) {
            FileLog.d("VFACTORY gmv0.j() failed: " + (e.cause ?: e))
        }
    }

    private fun readFactory(loader: ClassLoader): Any? = try {
        val envCls = loader.loadClass("com.viber.voip.ViberEnv")
        val fld = envCls.getDeclaredField("sViberFactory").apply { isAccessible = true }
        fld.get(null)
    } catch (e: Throwable) { null }

    fun setArgV0(name: String) {
        FileLog.d("ARGV0 before=" + android.os.Process.myProcessName())
        try {
            val ok = NativeBridge.setStaticStringByName(Class.forName("android.os.Process"), "sArgV0", name)
            FileLog.d("ARGV0 native result=" + ok + " now=" + android.os.Process.myProcessName())
        } catch (e: Throwable) {
            FileLog.d("ARGV0 native failed: " + e)
        }
    }

    private fun scanProcessName() {
        FileLog.d("PNAME2 begin")
        try {
            val at = Class.forName("android.app.ActivityThread")
            FileLog.d("PNAME2 Process.myProcessName=" + android.os.Process.myProcessName())
            FileLog.d("PNAME2 Application.getProcessName=" + Application.getProcessName())
            FileLog.d("PNAME2 ActivityThread.currentProcessName=" +
                    try { at.getDeclaredMethod("currentProcessName").invoke(null) } catch (e: Throwable) { "failed " + e })
            try {
                val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
                val bf = at.getDeclaredField("mBoundApplication").apply { isAccessible = true }
                val bound = bf.get(thread)!!
                val pf = bound.javaClass.getDeclaredField("processName").apply { isAccessible = true }
                FileLog.d("PNAME2 mBoundApplication.processName=" + pf.get(bound))
            } catch (e: Throwable) { FileLog.d("PNAME2 bound read failed: " + e) }
        } catch (e: Throwable) { FileLog.d("PNAME2 failed: " + e) }
    }
}