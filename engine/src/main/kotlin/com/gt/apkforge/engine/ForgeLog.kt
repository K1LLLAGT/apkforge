package com.gt.apkforge.engine

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Receives engine output. Mirrors the CLI's info/ok/warn/err + decompile.log split. */
interface ForgeLog {
    /** User-facing status line (CLI: `[*] ...`). Also goes to decompile.log. */
    fun info(msg: String)
    fun ok(msg: String) = info(msg)
    fun warn(msg: String) = info("[!] $msg")
    /** Tool chatter (jadx/ARSCLib/smali); CLI sends it to decompile.log only. */
    fun detail(msg: String) {}
    /** Coarse progress for the current stage, 0..1, or null for indeterminate. */
    fun progress(stage: Stage, fraction: Float?) {}

    companion object {
        val NONE = object : ForgeLog { override fun info(msg: String) {} }

        fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
    }
}

enum class Stage(val label: String) {
    PREPARE("prepare input"),
    MERGE("merge splits"),
    APKTOOL("resources + smali"),
    JADX("jadx"),
    META("write info"),
    REBUILD("rebuild"),
    SIGN("sign"),
}

/** Tees every line into decompile.log (append) and forwards to [delegate]. */
class FileTeeLog(private val file: File, private val delegate: ForgeLog) : ForgeLog {
    private val writer = file.also { it.parentFile?.mkdirs() }.bufferedWriter()
    @Synchronized private fun write(s: String) { writer.appendLine(s); writer.flush() }
    override fun info(msg: String) { write("[*] $msg"); delegate.info(msg) }
    override fun ok(msg: String) { write("[+] $msg"); delegate.ok(msg) }
    override fun warn(msg: String) { write("[!] $msg"); delegate.warn(msg) }
    override fun detail(msg: String) { write(msg); delegate.detail(msg) }
    override fun progress(stage: Stage, fraction: Float?) = delegate.progress(stage, fraction)
    fun close() = runCatching { writer.close() }
}
