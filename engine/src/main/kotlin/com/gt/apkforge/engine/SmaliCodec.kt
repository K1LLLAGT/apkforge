package com.gt.apkforge.engine

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.VersionMap
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.reandroid.apk.ApkModuleEncoder
import com.reandroid.apk.DexDecoder
import com.reandroid.apk.DexEncoder
import com.reandroid.apk.DexFileInputSource
import com.reandroid.archive.FileInputSource
import com.reandroid.archive.InputSource
import java.io.File
import java.io.IOException

/**
 * smali layout inside apktool/ follows APKEditor (`smali/classes`, `smali/classes2`, ...)
 * so `java -jar APKEditor.jar b -i apktool/` can also build an app-made project.
 * The dex format version of each file is kept in `smali/<name>/.dexversion` for rebuild.
 */
object SmaliCodec {
    const val SMALI_DIR = "smali"
    private const val VERSION_FILE = ".dexversion"

    fun dirNameFor(dexName: String) = dexName.removeSuffix(".dex")

    class Decoder(private val log: ForgeLog, private val jobs: Int) : DexDecoder {
        var classes = 0
            private set

        override fun decodeDex(src: DexFileInputSource, mainDirectory: File) {
            val name = File(src.alias).name
            val out = File(File(mainDirectory, SMALI_DIR), dirNameFor(name))
            val bytes = src.openStream().use { it.readBytes() }
            if (bytes.size < 8) throw IOException("bad dex: $name")
            val version = String(bytes, 4, 3, Charsets.US_ASCII).toIntOrNull() ?: 35
            val dex = DexBackedDexFile(Opcodes.forDexVersion(version), bytes)
            val opts = BaksmaliOptions().apply {
                apiLevel = VersionMap.mapDexVersionToApi(version).coerceAtLeast(15)
                parameterRegisters = true
                localsDirective = true
                sequentialLabels = true
                debugInfo = true
                implicitReferences = false
                accessorComments = false
            }
            log.detail("baksmali $name (dex $version, ${dex.classes.size} classes) -> ${out.relativeTo(mainDirectory.parentFile)}")
            out.mkdirs()
            if (!Baksmali.disassembleDexFile(dex, out, jobs, opts)) throw IOException("baksmali failed on $name")
            File(out, VERSION_FILE).writeText("$version\n")
            classes += dex.classes.size
        }
    }

    /** Assembles every `smali/<name>/` directory back into `<name>.dex`. */
    class Encoder(private val tmpDir: File, private val log: ForgeLog, private val jobs: Int) : DexEncoder {
        override fun buildDexFiles(encoder: ApkModuleEncoder, mainDirectory: File): List<InputSource> {
            val root = File(mainDirectory, SMALI_DIR)
            val dirs = root.listFiles { f -> f.isDirectory }.orEmpty().sortedWith(compareBy({ it.name.length }, { it.name }))
            tmpDir.mkdirs()
            return dirs.map { dir ->
                val version = File(dir, VERSION_FILE).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 35
                val dexName = dir.name + ".dex"
                val out = File(tmpDir, dexName)
                val opts = SmaliOptions().apply {
                    apiLevel = VersionMap.mapDexVersionToApi(version).coerceAtLeast(15)
                    outputDexFile = out.absolutePath
                    this.jobs = this@Encoder.jobs
                    verboseErrors = false
                }
                log.info("  smali ${dir.name}/ -> $dexName")
                if (!Smali.assemble(opts, listOf(dir.absolutePath))) throw IOException("smali failed on ${dir.name}/ (see log)")
                FileInputSource(out, dexName)
            }
        }
    }
}
