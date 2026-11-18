package com.gt.apkforge.engine

import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleRawDecoder
import com.reandroid.apk.ApkModuleXmlDecoder
import java.io.File

/**
 * The CLI's `apktool d` step, done with ARSCLib (resources/manifest -> XML) + baksmali.
 * Status strings match the CLI so info.txt / `apkforge list` read the same.
 */
class ResourceStage(private val log: ForgeLog, private val jobs: Int) {

    fun run(apk: File, out: File): String {
        log.progress(Stage.APKTOOL, null)
        try {
            decode(apk, out, xml = true)
            return "ok"
        } catch (t: Throwable) {
            if (t is VirtualMachineError && t !is OutOfMemoryError) throw t
            log.detail(ForgeLog.stackTrace(t))
            log.warn("  resource decode failed (${t.javaClass.simpleName}: ${t.message}); retrying raw")
        }
        out.deleteRecursively()
        return try {
            decode(apk, out, xml = false)
            "ok (-r: resources not decoded)"
        } catch (t: Throwable) {
            if (t is VirtualMachineError && t !is OutOfMemoryError) throw t
            log.detail(ForgeLog.stackTrace(t))
            "FAILED"
        }
    }

    private fun decode(apk: File, out: File, xml: Boolean) {
        val module = ApkModule.loadApkFile(InputPreparer.arscLogger(log), apk)
        try {
            val decoder = if (xml) ApkModuleXmlDecoder(module) else ApkModuleRawDecoder(module)
            decoder.setApkLogger(InputPreparer.arscLogger(log))
            val smali = SmaliCodec.Decoder(log, jobs)
            decoder.setDexDecoder(smali)
            decoder.decode(out)
            log.detail("decoded ${smali.classes} classes to smali")
        } finally {
            module.close()
        }
    }
}
