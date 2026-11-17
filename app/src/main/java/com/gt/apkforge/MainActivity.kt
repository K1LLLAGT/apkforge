package com.gt.apkforge

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.gt.apkforge.engine.Forge
import com.gt.apkforge.engine.ForgeLog
import com.gt.apkforge.engine.ProjectLayout
import com.gt.apkforge.engine.Rebuilder
import com.gt.apkforge.engine.Stage
import java.io.File
import kotlin.concurrent.thread

/**
 * Phase 1 engine test bench: run the in-process engine on a picked file or installed app
 * and show its log. Replaced by the full UI in later phases.
 */
class MainActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()
    private var busy by mutableStateOf(false)
    private var stage by mutableStateOf("")
    private var lastProject by mutableStateOf<File?>(null)
    private var lastApk by mutableStateOf<File?>(null)

    private val outRoot get() = File(getExternalFilesDir(null), "projects").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Bench() }
            }
        }
    }

    @Composable
    private fun Bench() {
        var showApps by remember { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runImport(uri)
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ApkForge engine bench", style = MaterialTheme.typography.titleLarge)
            Text("out: $outRoot", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pick file") }
                Button(enabled = !busy, onClick = { showApps = true }) { Text("Installed app") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && lastProject != null, onClick = { runRebuild() }) { Text("Rebuild + sign") }
                Button(enabled = !busy && lastApk != null, onClick = { install(lastApk!!) }) { Text("Install") }
            }
            if (busy) {
                Text(stage, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val state = rememberLazyListState()
            LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
        if (showApps) AppPicker(onDismiss = { showApps = false }) { app ->
            showApps = false
            runInstalled(app)
        }
    }

    @Composable
    private fun AppPicker(onDismiss: () -> Unit, onPick: (ApplicationInfo) -> Unit) {
        var filter by remember { mutableStateOf("") }
        val apps = remember {
            packageManager.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            title = { Text("Installed apps") },
            text = {
                Column {
                    OutlinedTextField(filter, { filter = it }, label = { Text("filter") }, singleLine = true)
                    LazyColumn {
                        items(apps.filter {
                            filter.isBlank() || it.packageName.contains(filter, true) ||
                                it.loadLabel(packageManager).contains(filter, true)
                        }) { app ->
                            TextButton(onClick = { onPick(app) }) {
                                Text("${app.loadLabel(packageManager)}\n${app.packageName}" +
                                    (app.splitSourceDirs?.let { " (+${it.size} splits)" } ?: ""))
                            }
                        }
                    }
                }
            },
        )
    }

    private val uiLog = object : ForgeLog {
        override fun info(msg: String) = post("[*] $msg")
        override fun ok(msg: String) = post("[+] $msg")
        override fun warn(msg: String) = post("[!] $msg")
        override fun progress(stage: Stage, fraction: Float?) {
            runOnUiThread {
                this@MainActivity.stage = stage.label + (fraction?.let { " ${(it * 100).toInt()}%" } ?: "")
            }
        }
    }

    private fun post(s: String) = runOnUiThread { lines += s }

    private fun background(name: String, block: () -> Unit) {
        busy = true
        lines.clear()
        thread(name = name) {
            val t0 = System.currentTimeMillis()
            try {
                block()
            } catch (t: Throwable) {
                post("[x] ${t.javaClass.simpleName}: ${t.message}")
                ForgeLog.stackTrace(t).lineSequence().take(15).forEach { post(it) }
            } finally {
                post("(${(System.currentTimeMillis() - t0) / 1000}s, heap max ${Runtime.getRuntime().maxMemory() shr 20} MB)")
                runOnUiThread { busy = false }
            }
        }
    }

    private fun runImport(uri: Uri) = background("decompile") {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "picked.apk"
        if (!ProjectLayout.isApkLike(name)) post("[!] $name: not .apk/.apks/.xapk/.apkm/.apk+ — trying anyway")
        val dir = File(cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, name)
        post("[*] copying $name")
        contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } }
        val r = Forge().decompile(file, outRoot, uiLog, sourceLabel = name)
        lastProject = r.dir
        file.delete()
    }

    private fun runInstalled(app: ApplicationInfo) = background("decompile") {
        val paths = listOf(File(app.sourceDir)) + app.splitSourceDirs.orEmpty().map(::File)
        post("[*] ${app.packageName}: ${paths.size} APK(s)")
        val r = Forge().decompile(File(app.sourceDir), outRoot, uiLog, slug = app.packageName,
            extraSplits = if (paths.size > 1) paths else emptyList(), sourceLabel = app.packageName)
        lastProject = r.dir
    }

    private fun runRebuild() {
        val p = lastProject ?: return
        background("rebuild") { lastApk = Rebuilder(uiLog).rebuild(p) }
    }

    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
