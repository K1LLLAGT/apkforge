package com.gt.apkforge.engine

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkSplitInfoCleaner
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class PreparedInput(val apk: File, val note: String)

/** Port of cli/apkforge prepare_input + pick_base + merge_splits, in-process. */
class InputPreparer(private val log: ForgeLog, private val merge: Boolean = true) {

    /**
     * [src] is a single APK, a bundle (.apks/.xapk/.apkm/.apk+), or a directory of split APKs.
     * [extraSplits] lets callers (installed-app picker) pass base + split paths directly.
     */
    fun prepare(src: File, layout: ProjectLayout, extraSplits: List<File> = emptyList()): PreparedInput {
        val splits = layout.splits
        splits.mkdirs()
        when {
            extraSplits.isNotEmpty() -> extraSplits.forEach { it.copyTo(File(splits, it.name), overwrite = true) }
            src.isDirectory -> {
                val apks = src.listFiles { f -> f.isFile && f.name.endsWith(".apk") }.orEmpty()
                if (apks.isEmpty()) throw IOException("no .apk files in $src")
                apks.forEach { it.copyTo(File(splits, it.name), overwrite = true) }
            }
            else -> {
                src.copyTo(File(layout.input, "original"), overwrite = true)
                val names = try {
                    ZipFile(src).use { z -> z.entries().asSequence().map { it.name }.toList() }
                } catch (e: IOException) {
                    throw IOException("not a zip/APK: $src", e)
                }
                if ("AndroidManifest.xml" in names) {
                    val app = File(layout.input, "app.apk")
                    src.copyTo(app, overwrite = true)
                    splits.delete()
                    return PreparedInput(app, "single APK")
                }
                if (names.none { it.endsWith(".apk") }) throw IOException("no manifest and no embedded .apk in $src")
                ZipFile(src).use { z ->
                    z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk") }.forEach { e ->
                        val out = File(splits, File(e.name).name) // unzip -j
                        z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    }
                }
                val others = names.filter { !it.endsWith(".apk") && !it.endsWith("/") }.take(50)
                if (others.isNotEmpty()) File(layout.input, "other_entries.txt").writeText(others.joinToString("\n", postfix = "\n"))
            }
        }
        val apks = splits.listFiles { f -> f.name.endsWith(".apk") }.orEmpty().toList()
        val base = pickBase(apks) ?: throw IOException("no .apk files found")
        if (apks.size == 1) return PreparedInput(base, "bundle: 1 APK")
        if (merge) {
            log.info("  merging ${apks.size} splits (ARSCLib)")
            log.progress(Stage.MERGE, null)
            val merged = File(layout.input, "merged.apk")
            try {
                mergeSplits(splits, merged, log)
                if (merged.length() > 0) return PreparedInput(merged, "bundle: ${apks.size} splits merged")
            } catch (t: Throwable) {
                if (t is VirtualMachineError && t !is OutOfMemoryError) throw t
                log.detail(ForgeLog.stackTrace(t))
            }
            log.warn("  merge failed — using base APK")
        }
        return PreparedInput(base, "bundle: ${apks.size} splits, base only (${base.name})")
    }

    companion object {
        fun pickBase(apks: List<File>): File? {
            apks.firstOrNull { it.name == "base.apk" }?.let { return it }
            return apks.filter { !it.name.startsWith("split_config") && !it.name.startsWith("config.") }
                .maxByOrNull { it.length() }
                ?: apks.maxByOrNull { it.length() }
        }

        /** APKEditor `m` equivalent: merge a directory of splits into one unsigned APK. */
        fun mergeSplits(splitsDir: File, out: File, log: ForgeLog) {
            ApkBundle().use { bundle ->
                bundle.setAPKLogger(arscLogger(log))
                bundle.loadApkDirectory(splitsDir, false)
                val merged = bundle.mergeModules()
                try {
                    merged.setApkSignatureBlock(null)
                    if (merged.hasAndroidManifest()) ApkSplitInfoCleaner.cleanSplitInfo(merged)
                    merged.writeApk(out)
                } finally {
                    merged.close()
                }
            }
        }

        fun arscLogger(log: ForgeLog) = object : APKLogger {
            override fun logMessage(msg: String) = log.detail(msg)
            override fun logError(msg: String, tr: Throwable?) =
                log.detail("E: $msg" + (tr?.let { "\n" + ForgeLog.stackTrace(it) } ?: ""))
            override fun logVerbose(msg: String) {}
        }
    }
}
