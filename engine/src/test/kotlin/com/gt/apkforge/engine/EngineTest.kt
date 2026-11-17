package com.gt.apkforge.engine

import com.gt.apkforge.engine.compat.Jdk11Compat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater

class EngineTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun slugifyMatchesCli() {
        // expected values produced by cli/apkforge slugify()
        assertEquals("app_debug", ProjectLayout.slugify("app-debug.apk"))
        assertEquals("com.whatsapp", ProjectLayout.slugify("/sdcard/x/com.whatsapp.apks"))
        assertEquals("my_app_v1.2", ProjectLayout.slugify("My App v1.2.xapk"))
        assertEquals("foo", ProjectLayout.slugify("Foo.apk+"))
        assertEquals("bar", ProjectLayout.slugify("__bar__.apkm"))
    }

    @Test fun apkLike() {
        assertTrue(ProjectLayout.isApkLike("a.APK"))
        assertTrue(ProjectLayout.isApkLike("a.apk+"))
        assertTrue(ProjectLayout.isApkLike("a.xapk"))
        assertFalse(ProjectLayout.isApkLike("a.zip"))
    }

    @Test fun pickBasePrefersBaseThenLargestNonConfig() {
        val d = tmp.newFolder()
        fun f(n: String, size: Int) = File(d, n).apply { writeBytes(ByteArray(size)) }
        val cfg = f("split_config.arm64_v8a.apk", 900)
        val feat = f("feature.apk", 500)
        assertEquals(feat, InputPreparer.pickBase(listOf(cfg, feat)))
        val base = f("base.apk", 10)
        assertEquals(base, InputPreparer.pickBase(listOf(cfg, feat, base)))
        assertEquals(cfg, InputPreparer.pickBase(listOf(cfg)))
    }

    @Test fun infoParsing() {
        val p = ProjectLayout(tmp.newFolder("proj"))
        p.info.writeText("source:  /a/b.apk\ntype:    single APK\n\napktool: ok\njadx:    ok with errors (~3, see log)\n")
        val info = p.readInfo()
        assertEquals("single APK", info["type"])
        assertEquals("ok with errors (~3, see log)", info["jadx"])
    }

    @Test fun compatShims() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        assertTrue(data.contentEquals(Jdk11Compat.readAllBytes(ByteArrayInputStream(data))))
        assertEquals(10, Jdk11Compat.readNBytes(ByteArrayInputStream(data), 10).size)
        val bos = ByteArrayOutputStream().apply { write("héllo".toByteArray()) }
        assertEquals("héllo", Jdk11Compat.toString(bos, Charsets.UTF_8))

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true).apply { setInput(data); finish() }
        val buf = ByteArray(200_000)
        val n = deflater.deflate(buf)
        val inflater = Inflater(true)
        Jdk11Compat.setInput(inflater, ByteBuffer.wrap(buf, 0, n))
        val out = ByteArray(data.size)
        assertEquals(data.size, inflater.inflate(out))
        assertTrue(data.contentEquals(out))
        assertEquals("java.lang.String[]", Jdk11Compat.getTypeName(Array<String>::class.java))
    }

    @Test fun debugKeyLoads() {
        val key = SigningKey.debug()
        assertEquals("RSA", key.privateKey.algorithm)
        assertTrue(key.certs.single().subjectX500Principal.name.contains("ApkForge"))
    }

    @Test fun jadxStatusStrings() {
        val d = tmp.newFolder("jadx")
        assertEquals("FAILED", JadxStage.status(d, 0))
        File(d, "A.java").writeText("class A {}")
        assertEquals("ok", JadxStage.status(d, 0))
        assertEquals("ok with errors (~4, see log)", JadxStage.status(d, 4))
    }
}
