package com.gt.apkforge.engine

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** A private key + certificate chain used to sign rebuilt APKs (v1 + v2 + v3, apksig). */
class SigningKey(val name: String, val privateKey: PrivateKey, val certs: List<X509Certificate>) {
    companion object {
        /**
         * ApkForge's built-in debug key (like ~/.android/debug.keystore in the CLI). Public in the
         * repo on purpose: it only proves "rebuilt by ApkForge", never use it for releases.
         */
        fun debug(): SigningKey {
            val key = res("debug-key.pk8").use { it.readBytes() }
            val cert = res("debug-cert.der").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            val pk = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(key))
            return SigningKey("apkforge-debug", pk, listOf(cert))
        }

        /** PKCS12 (.p12/.pfx) or, on Android, BKS keystores. `alias` null = first key entry. */
        fun fromKeyStore(input: InputStream, type: String, storePass: CharArray, alias: String?, keyPass: CharArray?): SigningKey {
            val ks = KeyStore.getInstance(type)
            input.use { ks.load(it, storePass) }
            val a = alias ?: ks.aliases().toList().first { ks.isKeyEntry(it) }
            val pk = ks.getKey(a, keyPass ?: storePass) as PrivateKey
            val chain = ks.getCertificateChain(a).map { it as X509Certificate }
            return SigningKey(a, pk, chain)
        }

        private fun res(name: String): InputStream =
            SigningKey::class.java.getResourceAsStream("/com/gt/apkforge/engine/$name")
                ?: error("missing engine resource $name")
    }
}

object Signer {
    fun sign(input: File, output: File, key: SigningKey = SigningKey.debug(), minSdk: Int? = null) {
        val cfg = ApkSigner.SignerConfig.Builder("CERT", key.privateKey, key.certs).build()
        val builder = ApkSigner.Builder(listOf(cfg))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setCreatedBy("ApkForge")
        if (minSdk != null) builder.setMinSdkVersion(minSdk)
        output.parentFile?.mkdirs()
        builder.build().sign()
        File(output.path + ".idsig").delete()
    }

    /** Returns null when verified, else the errors (apksigner verify equivalent). */
    fun verify(apk: File): String? {
        val r = ApkVerifier.Builder(apk).build().verify()
        if (r.isVerified) return null
        return (r.errors + r.v1SchemeSigners.flatMap { it.errors } + r.v2SchemeSigners.flatMap { it.errors } +
            r.v3SchemeSigners.flatMap { it.errors }).joinToString("; ").ifEmpty { "not verified" }
    }
}
