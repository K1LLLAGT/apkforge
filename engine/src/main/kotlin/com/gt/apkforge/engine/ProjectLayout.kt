package com.gt.apkforge.engine

import java.io.File
import java.util.Locale

/**
 * The per-app project layout shared with cli/apkforge (the contract the UI reads):
 *
 *   <out>/<slug>/
 *     input/        original + extracted/merged APKs
 *     apktool/      smali + decoded res (ARSCLib/APKEditor layout) -> rebuild
 *     jadx/         Java source as a Gradle project (Android Studio)
 *     dist/         rebuilt / signed APKs
 *     rebuild.sh    shortcut for `apkforge rebuild <this dir>`
 *     info.txt  decompile.log  .done
 */
class ProjectLayout(val dir: File) {
    val input get() = File(dir, "input")
    val splits get() = File(input, "splits")
    val apktool get() = File(dir, "apktool")
    val jadx get() = File(dir, "jadx")
    val dist get() = File(dir, "dist")
    val info get() = File(dir, "info.txt")
    val log get() = File(dir, "decompile.log")
    val done get() = File(dir, ".done")
    val rebuildSh get() = File(dir, "rebuild.sh")
    val unsignedApk get() = File(dist, "unsigned.apk")
    val signedApk get() = File(dist, "rebuilt-signed.apk")
    val slug: String get() = dir.name

    val isDone get() = done.isFile

    /** info.txt parsed as `key: value` lines (first occurrence wins). */
    fun readInfo(): Map<String, String> {
        if (!info.isFile) return emptyMap()
        val map = LinkedHashMap<String, String>()
        info.readLines().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) map.putIfAbsent(line.substring(0, i).trim(), line.substring(i + 1).trim())
        }
        return map
    }

    companion object {
        val APK_EXTENSIONS = listOf(".apk+", ".apks", ".xapk", ".apkm", ".apk")

        fun isApkLike(name: String): Boolean {
            val n = name.lowercase(Locale.ROOT)
            return APK_EXTENSIONS.any { n.endsWith(it) }
        }

        /** Same rules as the CLI's slugify(). */
        fun slugify(name: String): String {
            var s = File(name).name
            for (ext in APK_EXTENSIONS) if (s.endsWith(ext)) { s = s.dropLast(ext.length); break }
            return s.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9.]+"), "_")
                .trim('_')
                .ifEmpty { "app" }
        }
    }
}
