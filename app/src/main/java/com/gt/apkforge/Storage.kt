package com.gt.apkforge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

object Storage {
    /** Shared location, same as the CLI's default; needs all-files access on API 30+. */
    private val shared get() = File(Environment.getExternalStorageDirectory(), "ApkForge/projects")

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else false

    /** /sdcard/ApkForge/projects when permitted, else the app-specific external dir. */
    fun projectsRoot(ctx: Context): File {
        if (hasAllFilesAccess() && (shared.isDirectory || shared.mkdirs())) return shared
        return File(ctx.getExternalFilesDir(null), "projects").apply { mkdirs() }
    }

    fun allFilesAccessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
}
