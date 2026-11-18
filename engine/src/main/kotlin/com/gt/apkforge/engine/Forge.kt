package com.gt.apkforge.engine

import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class DecompileOptions(
    val apktool: Boolean = true,
    val jadx: Boolean = true,
    val merge: Boolean = true,
    val force: Boolean = false,
    val jadxOptions: JadxOptions = JadxOptions(),
    val threads: Int = Runtime.getRuntime().availableProcessors(),
)

enum class ResultStatus(val tag: String) { OK("OK  "), PART("PART"), FAIL("FAIL"), SKIP("SKIP") }

data class DecompileResult(
    val status: ResultStatus,
    val slug: String,
    val dir: File,
    val note: String = "",
    val apktoolStatus: String = "skipped",
    val jadxStatus: String = "skipped",
    val error: String? = null,
) {
    /** One line of batch summary.txt, same format as the CLI. */
    val summaryLine: String
        get() = when (status) {
            ResultStatus.SKIP -> "SKIP  $slug"
            ResultStatus.FAIL -> if (error != null) "FAIL  $slug ($error)" else "FAIL  $slug  [$note]"
            else -> "${status.tag}  $slug  [$note]"
        }
}

/**
 * In-process ApkForge engine. [decompile] is a port of cli/apkforge `decompile_one`
 * and produces the same project layout (see [ProjectLayout]).
 */
class Forge(
    private val options: DecompileOptions = DecompileOptions(),
    private val jadxRunner: JadxRunner = JadxStage,
) {
    /**
     * @param src APK, bundle or directory of splits
     * @param extraSplits installed-app split paths (base first), used instead of [src] contents
     * @param sourceLabel what goes into info.txt "source:" (defaults to [src] path)
     */
    fun decompile(
        src: File,
        outRoot: File,
        log: ForgeLog,
        slug: String = ProjectLayout.slugify(src.name),
        extraSplits: List<File> = emptyList(),
        sourceLabel: String = src.path,
    ): DecompileResult {
        val layout = ProjectLayout(File(outRoot, slug))
        if (layout.isDone && !options.force) {
            log.ok("already done: $slug (use -f to redo)")
            return DecompileResult(ResultStatus.SKIP, slug, layout.dir)
        }
        layout.dir.deleteRecursively()
        layout.dir.mkdirs()
        val tee = FileTeeLog(layout.log, log)
        try {
            return run(src, layout, tee, extraSplits, sourceLabel)
        } finally {
            tee.close()
        }
    }

    private fun run(src: File, layout: ProjectLayout, log: FileTeeLog, extraSplits: List<File>, sourceLabel: String): DecompileResult {
        log.progress(Stage.PREPARE, null)
        val input = try {
            InputPreparer(log, options.merge).prepare(src, layout, extraSplits)
        } catch (t: Throwable) {
            if (t is VirtualMachineError && t !is OutOfMemoryError) throw t
            log.warn("bad input: ${t.message}")
            log.detail(ForgeLog.stackTrace(t))
            return DecompileResult(ResultStatus.FAIL, layout.slug, layout.dir, error = "bad input")
        }
        log.info("  ${input.note}")
        var apktool = "skipped"
        var jadx = "skipped"
        if (options.apktool) {
            log.info("  resources + smali (ARSCLib/baksmali)")
            apktool = ResourceStage(log, options.threads).run(input.apk, layout.apktool)
        }
        if (options.jadx) {
            log.info("  jadx --export-gradle")
            jadx = try {
                jadxRunner.run(input.apk, layout.jadx, options.jadxOptions, log)
            } catch (t: Throwable) {
                if (t is VirtualMachineError && t !is OutOfMemoryError) throw t
                log.detail(ForgeLog.stackTrace(t))
                JadxStage.status(layout.jadx, 1, t).let { if (it == "FAILED") it else "$it" }
            }
        }
        log.progress(Stage.META, null)
        writeProjectMeta(layout, sourceLabel, input, apktool, jadx, log)
        val st = when {
            apktool == "FAILED" && jadx == "FAILED" -> ResultStatus.FAIL
            apktool == "FAILED" || jadx == "FAILED" -> ResultStatus.PART
            else -> ResultStatus.OK
        }
        if (st != ResultStatus.FAIL) layout.done.writeText("")
        log.ok("  apktool: $apktool | jadx: $jadx")
        log.ok("  -> ${layout.dir}")
        return DecompileResult(st, layout.slug, layout.dir, input.note, apktool, jadx)
    }

    private fun writeProjectMeta(
        layout: ProjectLayout, src: String, input: PreparedInput, apktool: String, jadx: String, log: ForgeLog,
    ) {
        layout.rebuildSh.writeText(
            "#!/usr/bin/env bash\nexec apkforge rebuild \"\$(cd \"\$(dirname \"\$0\")\" && pwd)\" \"\$@\"\n",
        )
        layout.rebuildSh.setExecutable(true, false)
        val badging = try {
            ApkMeta.read(input.apk).badgingLines()
        } catch (t: Throwable) {
            if (t is VirtualMachineError && t !is OutOfMemoryError) throw t
            log.detail("badging failed: ${t.message}")
            emptyList()
        }
        val date = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        layout.info.writeText(buildString {
            appendLine("source:  $src")
            appendLine("input:   ${input.apk.relativeTo(layout.dir).invariantSeparatorsPath}")
            appendLine("type:    ${input.note}")
            appendLine("date:    $date")
            appendLine("engine:  $ENGINE_ID")
            badging.forEach { appendLine(it) }
            appendLine()
            appendLine("apktool: $apktool")
            appendLine("jadx:    $jadx")
        })
    }

    companion object {
        const val VERSION = "0.3.0"
        val ENGINE_ID = "apkforge-engine $VERSION (ARSCLib 1.4.0, smali 3.0.9, jadx 1.5.6)"
    }
}
