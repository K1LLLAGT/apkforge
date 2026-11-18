package com.gt.apkforge.engine

import com.reandroid.apk.ApkModuleEncoder
import com.reandroid.apk.ApkModuleRawEncoder
import com.reandroid.apk.ApkModuleXmlEncoder
import java.io.File
import java.io.IOException

/** `apkforge rebuild`: apktool/ -> dist/unsigned.apk -> align + sign -> dist/rebuilt-signed.apk */
class Rebuilder(private val log: ForgeLog, private val jobs: Int = Runtime.getRuntime().availableProcessors()) {

    fun rebuild(projectDir: File, key: SigningKey = SigningKey.debug()): File {
        val p = ProjectLayout(projectDir)
        if (!p.apktool.isDirectory) throw IOException("no apktool/ in $projectDir (decompiled with --java-only?)")
        p.dist.mkdirs()
        val tmp = File(p.dist, ".build")
        tmp.deleteRecursively()
        log.info("build apktool/ (ARSCLib + smali)")
        log.progress(Stage.REBUILD, null)
        build(p.apktool, p.unsignedApk, tmp)
        tmp.deleteRecursively()
        log.info("align + sign")
        log.progress(Stage.SIGN, null)
        Signer.sign(p.unsignedApk, p.signedApk, key)
        Signer.verify(p.signedApk)?.let { throw IOException("signature verify failed: $it") }
        log.ok(p.signedApk.path)
        log.warn("new signature: uninstall the original before installing")
        return p.signedApk
    }

    fun build(apktoolDir: File, out: File, tmp: File) {
        val logger = InputPreparer.arscLogger(log)
        // XML layout has resources/ (decoded table); raw layout keeps resources.arsc as-is.
        val xml = File(apktoolDir, "resources").isDirectory
        val encoder: ApkModuleEncoder = if (xml) ApkModuleXmlEncoder() else ApkModuleRawEncoder()
        encoder.setApkLogger(logger)
        encoder.setDexEncoder(SmaliCodec.Encoder(tmp, log, jobs))
        encoder.scanDirectory(apktoolDir)
        val module = encoder.apkModule
        try {
            module.setAPKLogger(logger)
            module.setApkSignatureBlock(null)
            // drop stale v1 signature files from the original
            module.zipEntryMap.removeIf(java.util.regex.Pattern.compile("^META-INF/[^/]+\\.(SF|RSA|DSA|EC|MF)$"))
            out.parentFile?.mkdirs()
            module.writeApk(out)
        } finally {
            module.close()
        }
    }
}
