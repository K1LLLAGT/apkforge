package com.gt.apkforge.engine

import com.gt.apkforge.engine.log.ForgeSlf4j
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.core.export.ExportGradleType
import java.io.File

data class JadxOptions(
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    val deobf: Boolean = false,
    val showBadCode: Boolean = true,
    val exportGradle: Boolean = true,
)

/** How the jadx step runs: in-process (CLI/tests) or in the app's separate `:jadx` process. */
fun interface JadxRunner {
    /** Returns the CLI-compatible status string: ok / ok with errors (...) / FAILED. */
    fun run(apk: File, outDir: File, options: JadxOptions, log: ForgeLog): String
}

/** `jadx --export-gradle --show-bad-code -j N [--deobf]` via jadx-core. */
object JadxStage : JadxRunner {
    @Synchronized
    override fun run(apk: File, outDir: File, options: JadxOptions, log: ForgeLog): String {
        val args = JadxArgs().apply {
            setInputFile(apk)
            this.outDir = outDir
            threadsCount = options.threads
            isShowInconsistentCode = options.showBadCode
            isDeobfuscationOn = options.deobf
            exportGradleType = if (options.exportGradle) ExportGradleType.AUTO else null
            isSkipFilesSave = false
        }
        ForgeSlf4j.errors = 0
        ForgeSlf4j.sink = log
        var crash: Throwable? = null
        try {
            JadxDecompiler(args).use { jadx ->
                log.progress(Stage.JADX, null)
                jadx.load()
                log.detail("jadx: loaded ${jadx.classes.size} classes")
                jadx.save(500) { done, total ->
                    log.progress(Stage.JADX, if (total > 0) done.toFloat() / total else null)
                }
                ForgeSlf4j.errors = maxOf(ForgeSlf4j.errors, jadx.errorsCount)
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError && t !is OutOfMemoryError) throw t
            crash = t
            log.detail("jadx crashed: " + ForgeLog.stackTrace(t))
        } finally {
            ForgeSlf4j.sink = null
        }
        return status(outDir, ForgeSlf4j.errors, crash)
    }

    fun status(outDir: File, errors: Int, crash: Throwable? = null): String {
        val hasJava = outDir.walkTopDown().any { it.isFile && it.name.endsWith(".java") }
        return when {
            !hasJava -> "FAILED"
            errors == 0 && crash == null -> "ok"
            else -> "ok with errors (~$errors, see log)"
        }
    }
}
