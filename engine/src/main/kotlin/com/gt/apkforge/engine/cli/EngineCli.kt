package com.gt.apkforge.engine.cli

import com.gt.apkforge.engine.ApkMeta
import com.gt.apkforge.engine.DecompileOptions
import com.gt.apkforge.engine.Forge
import com.gt.apkforge.engine.ForgeLog
import com.gt.apkforge.engine.InputPreparer
import com.gt.apkforge.engine.JadxOptions
import com.gt.apkforge.engine.ProjectLayout
import com.gt.apkforge.engine.Rebuilder
import com.gt.apkforge.engine.ResultStatus
import com.gt.apkforge.engine.Signer
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Host-JVM front end for the in-process engine (used by CI and for parity checks against
 * cli/apkforge). Run: ./gradlew :engine:run --args="decompile app.apk -o out"
 */
fun main(argv: Array<String>) {
    val args = argv.toMutableList()
    val cmd = args.removeFirstOrNull() ?: "help"
    var out: File? = null
    var output: File? = null
    var force = false; var deobf = false; var merge = true; var apktool = true; var jadx = true
    var threads = Runtime.getRuntime().availableProcessors()
    val rest = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-o", "--out" -> out = File(args[++i])
            "--output" -> output = File(args[++i])
            "-f", "--force" -> force = true
            "--deobf" -> deobf = true
            "--no-merge" -> merge = false
            "--smali-only", "--no-jadx" -> jadx = false
            "--java-only", "--no-apktool" -> apktool = false
            "-j", "--threads" -> threads = args[++i].toInt()
            else -> rest += a
        }
        i++
    }
    val log = object : ForgeLog {
        override fun info(msg: String) = println("[*] $msg")
        override fun ok(msg: String) = println("[+] $msg")
        override fun warn(msg: String) = System.err.println("[!] $msg")
        override fun detail(msg: String) { if (System.getenv("APKFORGE_VERBOSE") != null) println("    $msg") }
    }
    val opts = DecompileOptions(apktool, jadx, merge, force, JadxOptions(threads = threads, deobf = deobf), threads)
    val root = out ?: File(System.getenv("APKFORGE_OUT") ?: "${System.getProperty("user.home")}/ApkForge/projects")
    when (cmd) {
        "decompile", "d" -> {
            require(rest.isNotEmpty()) { "usage: decompile <apk|bundle|dir>... [-o DIR]" }
            var rc = 0
            for (f in rest.map(::File)) {
                println("\n== ${f.name}")
                val r = Forge(opts).decompile(f, root, log)
                if (r.status == ResultStatus.FAIL) rc = 1
            }
            exitProcess(rc)
        }
        "batch", "b" -> {
            val src = File(rest.firstOrNull() ?: error("usage: batch <dir> [-o DIR]"))
            val files = src.listFiles().orEmpty().filter { it.isFile && ProjectLayout.isApkLike(it.name) }.sortedBy { it.name }
            require(files.isNotEmpty()) { "no matching APKs in $src" }
            val summary = files.mapIndexed { n, f ->
                println("\n== [${n + 1}/${files.size}] ${f.name}")
                Forge(opts).decompile(f, root, log).summaryLine
            }
            println("\n===== summary =====")
            summary.forEach(::println)
            File(root, "summary.txt").writeText(summary.joinToString("\n", postfix = "\n"))
        }
        "rebuild", "r" -> {
            val p = rest.firstOrNull()?.let { if (File(it).isDirectory) File(it) else File(root, it) } ?: error("usage: rebuild <project>")
            Rebuilder(log, threads).rebuild(p)
        }
        "merge" -> {
            val src = File(rest.firstOrNull() ?: error("usage: merge <bundle|dir> [--output out.apk]"))
            val dest = output ?: File(ProjectLayout.slugify(src.name) + "-merged.apk")
            val tmp = Files.createTempDirectory("apkforge-merge").toFile()
            try {
                val splits = if (src.isDirectory) src else File(tmp, "splits").also { d ->
                    d.mkdirs()
                    java.util.zip.ZipFile(src).use { z ->
                        z.entries().asSequence().filter { it.name.endsWith(".apk") }.forEach { e ->
                            z.getInputStream(e).use { s -> File(d, File(e.name).name).outputStream().use { s.copyTo(it) } }
                        }
                    }
                }
                InputPreparer.mergeSplits(splits, dest, log)
                log.ok(dest.path)
            } finally { tmp.deleteRecursively() }
        }
        "sign" -> {
            val src = File(rest.firstOrNull() ?: error("usage: sign <apk> [--output out.apk]"))
            val dest = output ?: File(src.path.removeSuffix(".apk") + "-signed.apk")
            Signer.sign(src, dest)
            Signer.verify(dest)?.let { error(it) }
            log.ok(dest.path)
        }
        "verify" -> {
            val src = File(rest.firstOrNull() ?: error("usage: verify <apk>"))
            val err = Signer.verify(src)
            if (err == null) log.ok("verified: $src") else { log.warn(err); exitProcess(1) }
        }
        "info" -> {
            val src = File(rest.firstOrNull() ?: error("usage: info <apk>"))
            ApkMeta.read(src).badgingLines().forEach(::println)
        }
        "version" -> println(Forge.ENGINE_ID)
        else -> println(
            """
            ${Forge.ENGINE_ID}
            commands: decompile <apk|bundle|dir>... | batch <dir> | rebuild <project> | merge <bundle|dir> [--output f]
                      sign <apk> [--output f] | verify <apk> | info <apk> | version
            options:  -o DIR  -f  --deobf  --no-merge  --smali-only  --java-only  -j N
            """.trimIndent(),
        )
    }
}
