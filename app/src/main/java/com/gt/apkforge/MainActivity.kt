package com.gt.apkforge

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
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
import com.gt.apkforge.engine.ProjectLayout
import java.io.File
import kotlin.concurrent.thread

/**
 * Engine test bench (jobs run in the :forge process): run the in-process engine on a picked file or installed app
 * and show its log. Replaced by the full UI in later phases.
 */
class MainActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()
    private var busy by mutableStateOf(false)
    private var stage by mutableStateOf("")
    private var lastProject by mutableStateOf<File?>(null)
    private var lastApk by mutableStateOf<File?>(null)

    private var hasAccess by mutableStateOf(Storage.hasAllFilesAccess())

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
            Text("out: ${Storage.projectsRoot(this@MainActivity)}", style = MaterialTheme.typography.bodySmall)
            if (!hasAccess && Build.VERSION.SDK_INT >= 30) {
                TextButton(onClick = { startActivity(Storage.allFilesAccessIntent(this@MainActivity)) }) {
                    Text("Grant all-files access (use /sdcard/ApkForge)")
                }
            }
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

    private var service: Messenger? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) { service = Messenger(b) }
        override fun onServiceDisconnected(n: ComponentName) {
            service = null
            if (busy) { lines += "[x] engine process died (likely out of memory)"; busy = false }
            if (!started) unbindOnce()
        }
    }
    private val replies = Messenger(Handler(Looper.getMainLooper()) { m ->
        val t = m.data.getString("t").orEmpty()
        when (m.what) {
            ForgeService.R_LINE -> lines += t
            ForgeService.R_STAGE -> stage = t
            ForgeService.R_DONE -> {
                val err = m.data.getString("error")
                err?.lines()?.forEachIndexed { i, l -> lines += if (i == 0) "[x] $l" else l }
                m.data.getString("project")?.let { if (err == null) lastProject = File(it) }
                m.data.getString("apk")?.let { lastApk = File(it) }
                lines += "(${(System.currentTimeMillis() - t0) / 1000}s, ${m.data.getString("heap")})"
                busy = false
                if (!started) unbindOnce()
            }
        }
        true
    })
    private var t0 = 0L
    private var bound = false
    private var started = false

    override fun onStart() {
        super.onStart()
        started = true
        hasAccess = Storage.hasAllFilesAccess()
        if (!bound) bound = bindService(Intent(this, ForgeService::class.java), conn, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        started = false
        // keep the connection while a job runs so a dying :forge process still reaches onServiceDisconnected
        if (!busy) unbindOnce()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindOnce()
    }

    private fun unbindOnce() {
        if (!bound) return
        bound = false
        unbindService(conn)
        service = null
    }

    private fun submit(what: Int, data: Bundle) {
        val s = service ?: run { lines += "[x] engine process not ready, try again"; return }
        lines.clear(); busy = true; t0 = System.currentTimeMillis()
        try { s.send(Message.obtain().also { it.what = what; it.data = data; it.replyTo = replies }) }
        catch (e: RemoteException) { lines += "[x] ${e.message}"; busy = false }
    }

    private fun runImport(uri: Uri) {
        busy = true; lines.clear()
        thread(name = "import") {
            try {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "picked.apk"
                val dir = File(cacheDir, "import").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, name)
                runOnUiThread { lines += "[*] copying $name" }
                contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } }
                runOnUiThread {
                    busy = false
                    if (!ProjectLayout.isApkLike(name)) lines += "[!] $name: not .apk/.apks/.xapk/.apkm/.apk+ — trying anyway"
                    submit(ForgeService.DECOMPILE, Bundle().apply {
                        putString("src", file.path); putString("out", Storage.projectsRoot(this@MainActivity).path)
                        putString("label", name); putBoolean("temp", true)
                    })
                }
            } catch (t: Throwable) {
                runOnUiThread { lines += "[x] ${t.javaClass.simpleName}: ${t.message}"; busy = false }
            }
        }
    }

    private fun runInstalled(app: ApplicationInfo) {
        val paths = listOf(File(app.sourceDir)) + app.splitSourceDirs.orEmpty().map(::File)
        submit(ForgeService.DECOMPILE, Bundle().apply {
            putString("src", app.sourceDir); putString("out", Storage.projectsRoot(this@MainActivity).path)
            putString("slug", app.packageName); putString("label", app.packageName)
            if (paths.size > 1) putStringArrayList("splits", ArrayList(paths.map { it.path }))
        })
        lines += "[*] ${app.packageName}: ${paths.size} APK(s)"
    }

    private fun runRebuild() {
        val p = lastProject ?: return
        submit(ForgeService.REBUILD, Bundle().apply { putString("project", p.path) })
    }

    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
