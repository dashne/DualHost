package com.ims134.dualhost

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.TreeSet
import java.util.zip.ZipFile

object GuestInstaller {

    class Result(val dir: File, val versionTag: String, val problem: String?)

    // The CPU type our clone processes actually run as
    private val nativeAbi: String = Build.SUPPORTED_ABIS[0]

    fun install(context: Context, pkg: String): Result {
        val pi = context.packageManager.getPackageInfo(pkg, 0)
        val ai = pi.applicationInfo!!
        val base = File(ai.sourceDir)
        val splits = (ai.splitSourceDirs ?: emptyArray()).map { File(it) }

        val tag = pi.lastUpdateTime.toString() + "_" + base.length() + "_" +
                base.lastModified() + "_" + splits.size
        val dir = File(context.filesDir, "guest/" + pkg + "/" + tag)
        val done = File(dir, ".complete2")

        if (!done.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            val copies = ArrayList<File>()
            copies.add(copy(base, File(dir, "base.apk")))
            for (s in splits) copies.add(copy(s, File(dir, s.name)))

            val abis = scanAbis(copies)
            File(dir, "abis.txt").writeText(abis.joinToString(","))
            val libCount = if (abis.contains(nativeAbi)) {
                extractLibs(copies, File(dir, "lib"), nativeAbi)
            } else 0
            done.writeText("ok")
            FileLog.d( "GuestInstaller: copied " + copies.size + " APK(s), native ABIs in app " +
                    abis + ", this process " + nativeAbi + ", extracted " + libCount + " lib(s)")
        }

        val abis = File(dir, "abis.txt").readText().split(",").filter { it.isNotEmpty() }
        var problem: String? = null
        if (abis.isNotEmpty() && !abis.contains(nativeAbi)) {
            problem = "Not compatible: this app has native code only for " + abis.joinToString(", ") +
                    ", but clones here run as " + nativeAbi
        }
        val names = File(dir, "names.txt")
        if (!names.exists()) {
            val apks = dir.listFiles { f -> f.name.endsWith(".apk") }?.toList() ?: emptyList()
            val hit = try { findAttestation(apks) } catch (e: Throwable) { null }
            names.writeText(if (hit != null) "host" else "own")
            FileLog.d( "GuestInstaller: " + pkg + " name mode=" +
                    (if (hit != null) "host (found " + hit + ")" else "own"))
        }
        return Result(dir, tag, problem)
    }

    // Apps that ask Google to prove they are the real app (Play Integrity, SafetyNet, App Check).
    // A clone can never pass that, so these apps must not believe they are the real package.
    private val attestMarks = listOf(
        "play/core/integrity", "play/integrity", "gms/safetynet", "appcheck/playintegrity"
    ).map { it.toByteArray(Charsets.ISO_8859_1) }

    private fun indexOf(buf: ByteArray, len: Int, pat: ByteArray): Int {
        var i = 0
        while (i <= len - pat.size) {
            var j = 0
            while (j < pat.size && buf[i + j] == pat[j]) j++
            if (j == pat.size) return i
            i++
        }
        return -1
    }

    private fun findAttestation(apks: List<File>): String? {
        val maxLen = attestMarks.maxOf { it.size }
        val buf = ByteArray(1 shl 16)
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.startsWith("classes") || !entry.name.endsWith(".dex")) continue
                    zip.getInputStream(entry).use { ins ->
                        var carry = 0
                        while (true) {
                            val n = ins.read(buf, carry, buf.size - carry)
                            if (n <= 0) break
                            val end = carry + n
                            for (m in attestMarks) {
                                if (indexOf(buf, end, m) >= 0) return String(m)
                            }
                            // keep the tail so a match split across two reads is not missed
                            carry = minOf(maxLen - 1, end)
                            System.arraycopy(buf, end - carry, buf, 0, carry)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun copy(src: File, dst: File): File {
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        dst.setReadOnly()
        return dst
    }

    private fun scanAbis(apks: List<File>): Set<String> {
        val abis = TreeSet<String>()
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    val n = entry.name
                    if (n.startsWith("lib/") && n.endsWith(".so")) {
                        abis.add(n.split("/").getOrElse(1) { "" })
                    }
                }
            }
        }
        return abis
    }

    private fun extractLibs(apks: List<File>, libDir: File, abi: String): Int {
        var count = 0
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (name.startsWith("lib/" + abi + "/") && name.endsWith(".so")) {
                        libDir.mkdirs()
                        val out = File(libDir, name.substringAfterLast('/'))
                        zip.getInputStream(entry).use { i ->
                            out.outputStream().use { o -> i.copyTo(o) }
                        }
                        out.setReadOnly()
                        count++
                    }
                }
            }
        }
        return count
    }
}