package com.ims134.dualhost

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.jvm.java


object Helper {
    val TAG = "Helper"
    fun setArgV0(processName:String)
    {
        try {
            //val s = Process::class.java.getDeclaredMethod("setArgV0", String::class.java)
            val s = NativeBridge.getDeclaredMethod(Process::class.java,"setArgV0", arrayOf(String::class.java))
            if(s == null) Log.d(TAG, "$TAG::NativeBridge.getDeclaredMethod returns null method")

            s?.invoke(null,processName)

            if(Build.VERSION.SDK_INT >= 33)
                Log.d(TAG, "$TAG::current process name: ${Process.myProcessName()}")
        }
        catch (e: Throwable)
        {
            Log.d(TAG, "$TAG::setArgV0 Exception: $e")
        }
    }
}



