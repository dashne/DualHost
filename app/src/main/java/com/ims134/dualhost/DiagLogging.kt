package com.ims134.dualhost



object DiagLogging {
    /** Call from your getPackageInfo guest branch, once per process. */
    fun logGuestPkgInfo(info: android.content.pm.ApplicationInfo) {
        FileLog.d(
            "PM guest info: pkg=${info.packageName} src=${info.sourceDir} " +
                    "data=${info.dataDir} deData=${info.deviceProtectedDataDir} " +
                    "native=${info.nativeLibraryDir} uid=${info.uid} " +
                    "flags=0x${Integer.toHexString(info.flags)}")
    }
}