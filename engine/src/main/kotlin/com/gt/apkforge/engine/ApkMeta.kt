package com.gt.apkforge.engine

import com.reandroid.apk.ApkModule
import java.io.File

data class ApkMeta(
    val packageName: String?,
    val versionCode: Int?,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compileSdk: Int?,
    val label: String?,
    val dexCount: Int,
) {
    /** Same shape as the `aapt dump badging` lines the CLI writes into info.txt. */
    fun badgingLines(): List<String> = buildList {
        add("package: name='${packageName ?: ""}' versionCode='${versionCode ?: ""}' versionName='${versionName ?: ""}'" +
            (compileSdk?.let { " compileSdkVersion='$it'" } ?: ""))
        minSdk?.let { add("sdkVersion:'$it'") }
        targetSdk?.let { add("targetSdkVersion:'$it'") }
        label?.let { add("application-label:'$it'") }
    }

    companion object {
        fun read(apk: File): ApkMeta {
            val module = ApkModule.loadApkFile(apk)
            try {
                module.setLoadDefaultFramework(false)
                val m = if (module.hasAndroidManifest()) module.androidManifest else null
                val label = m?.let { manifest ->
                    manifest.applicationLabelString ?: runCatching {
                        manifest.applicationLabelReference?.let { ref ->
                            module.tableBlock.getResource(ref)?.get()?.valueAsString
                        }
                    }.getOrNull()
                }
                return ApkMeta(
                    packageName = m?.packageName,
                    versionCode = m?.versionCode,
                    versionName = m?.versionName,
                    minSdk = m?.minSdkVersion,
                    targetSdk = m?.targetSdkVersion,
                    compileSdk = m?.compileSdkVersion,
                    label = label,
                    dexCount = module.listDexFiles().size,
                )
            } finally {
                module.close()
            }
        }
    }
}
