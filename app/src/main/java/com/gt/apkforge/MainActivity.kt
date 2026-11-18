package com.gt.apkforge

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.material3.Checkbox
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
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/** Interim job console; the worker and queue live exclusively in `:forge`. */
class MainActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()
    private val jobs = mutableStateListOf<ForgeJob>()
    private var busy by mutableStateOf(false)
    private var stage by mutableStateOf("")
    private var lastProject by mutableStateOf<File?>(null)
    private var lastApk by mutableStateOf<File?>(null)
    private var hasAccess by mutableStateOf(false)
    private var service: Messenger? = null
    private var bound = false
    private var processDied = false

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            ForgeService.R_JOB_UPDATE -> message.data.getString("job")?.let(::updateJob)
            ForgeService.R_LOG_LINE -> message.data.getString("text")?.let { text ->
                val label = jobs.firstOrNull { it.id == message.data.getString("id") }?.label
                lines += if (label == null) text else "$label: $text"
            }
            ForgeService.R_STAGE -> {
                stage = message.data.getString("stage").orEmpty()
                val index = jobs.indexOfFirst { it.id == message.data.getString("id") }
                if (index >= 0) {
                    val updated = ForgeJob.fromJson(jobs[index].toJson())
                    updated.stage = stage
                    updated.fraction = if (message.data.containsKey("fraction")) {
                        message.data.getFloat("fraction")
                    } else null
                    jobs[index] = updated
                }
            }
            ForgeService.R_ERROR -> message.data.getString("error")?.let { lines += "[x] $it" }
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            send(ForgeService.SUBSCRIBE)
            if (processDied) lines += "[x] engine process died; interrupted jobs failed and queued jobs resumed"
            processDied = false
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            if (busy) {
                processDied = true
                lines += "[x] engine process died"
                busy = false
            }
        }
    }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasAccess = Storage.hasAllFilesAccess()
        requestFirstLaunchPermissions()
        intent.getStringExtra(EXTRA_PROJECT)?.let { path -> lastProject = File(path).takeIf { it.isDirectory } }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Bench() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PROJECT)?.let { path -> lastProject = File(path).takeIf { it.isDirectory } }
    }

    private fun requestFirstLaunchPermissions() {
        val requested = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("permissions", MODE_PRIVATE).getBoolean("notifications_requested", false)
        ) {
            requested += Manifest.permission.POST_NOTIFICATIONS
            getSharedPreferences("permissions", MODE_PRIVATE).edit().putBoolean("notifications_requested", true).apply()
        }
        if (Build.VERSION.SDK_INT in 26..29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requested += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (requested.isNotEmpty()) permissionRequest.launch(requested.toTypedArray())
    }

    @Composable
    private fun Bench() {
        var showApps by remember { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) runImport(uris)
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ApkForge jobs", style = MaterialTheme.typography.titleLarge)
            Text("out: ${Storage.projectsRoot(this@MainActivity)}", style = MaterialTheme.typography.bodySmall)
            if (!hasAccess && Build.VERSION.SDK_INT >= 30) {
                TextButton(onClick = { startActivity(Storage.allFilesAccessIntent(this@MainActivity)) }) {
                    Text("Grant all-files access (use /sdcard/ApkForge)")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "application/zip",
                    "*/*",
                )) }) { Text("Open files") }
                Button(onClick = { showApps = true }) { Text("Installed apps") }
                Button(enabled = lastProject != null, onClick = { runRebuild() }) { Text("Rebuild + sign") }
                Button(enabled = lastApk != null, onClick = { lastApk?.let(::install) }) { Text("Install") }
            }
            if (busy) {
                Text(stage, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            jobs.takeLast(5).forEach { job ->
                Text("${job.status}: ${job.label}" + job.stage.takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty())
                if (job.status == ForgeJob.RUNNING || job.status == ForgeJob.QUEUED) {
                    TextButton(onClick = { send(ForgeService.CANCEL, Bundle().apply { putString("id", job.id) }) }) {
                        Text("Cancel ${job.label}")
                    }
                }
            }
            val state = rememberLazyListState()
            LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
        if (showApps) AppPicker(onDismiss = { showApps = false }) { selected ->
            showApps = false
            runInstalled(selected)
        }
    }

    @Composable
    private fun AppPicker(onDismiss: () -> Unit, onPick: (List<ApplicationInfo>) -> Unit) {
        var filter by remember { mutableStateOf("") }
        val selected = remember { mutableStateListOf<String>() }
        val apps = remember {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        }
        val visible = apps.filter {
            filter.isBlank() || it.packageName.contains(filter, true) ||
                it.loadLabel(packageManager).contains(filter, true)
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    onPick(apps.filter { it.packageName in selected })
                }) { Text("Decompile ${selected.size}") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            title = { Text("Installed apps") },
            text = {
                Column {
                    OutlinedTextField(filter, { filter = it }, label = { Text("filter") }, singleLine = true)
                    LazyColumn {
                        items(visible, key = { it.packageName }) { app ->
                            Row {
                                Checkbox(
                                    checked = app.packageName in selected,
                                    onCheckedChange = { checked ->
                                        if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                                    },
                                )
                                TextButton(onClick = {
                                    if (app.packageName in selected) selected.remove(app.packageName)
                                    else selected.add(app.packageName)
                                }) {
                                    Text("${app.loadLabel(packageManager)}\n${app.packageName}" +
                                        (app.splitSourceDirs?.let { " (+${it.size} splits)" } ?: ""))
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        hasAccess = Storage.hasAllFilesAccess()
        if (!bound) bound = bindService(Intent(this, ForgeService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (service != null) send(ForgeService.UNSUBSCRIBE)
        unbindOnce()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindOnce()
    }

    private fun unbindOnce() {
        if (!bound) return
        bound = false
        unbindService(connection)
        service = null
    }

    private fun updateJob(json: String) {
        val job = try {
            ForgeJob.fromJson(JSONObject(json))
        } catch (e: Exception) {
            lines += "[x] invalid job update: ${e.message}"
            return
        }
        val index = jobs.indexOfFirst { it.id == job.id }
        if (index >= 0) jobs[index] = job else jobs.add(job)
        if (job.projectDir.isNotEmpty() && job.kind == ForgeJob.DECOMPILE && job.status == ForgeJob.DONE) {
            lastProject = File(job.projectDir)
        }
        if (job.apk.isNotEmpty()) lastApk = File(job.apk)
        busy = jobs.any { it.status == ForgeJob.RUNNING }
        if (job.status == ForgeJob.FAILED && job.logLines.lastOrNull()?.contains("interrupted") == true) {
            lines += "[x] ${job.label}: interrupted after engine process died"
        }
    }

    private fun send(what: Int, data: Bundle = Bundle()) {
        val target = service ?: run { lines += "[x] engine service not ready; try again"; return }
        try {
            target.send(Message.obtain().also {
                it.what = what
                it.data = data
                it.replyTo = replies
            })
        } catch (e: RemoteException) {
            lines += "[x] ${e.message}"
        }
    }

    private fun runImport(uris: List<Uri>) {
        thread(name = "apk-import") {
            val root = Storage.projectsRoot(this)
            val batchId = UUID.randomUUID().toString()
            val batch = mutableListOf<Pair<File, String>>()
            for (uri in uris) {
                var importDir: File? = null
                try {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }?.let { File(it).name } ?: "picked.apk"
                    if (!ProjectLayout.isApkLike(name)) {
                        runOnUiThread { lines += "[!] $name: not .apk/.apk+/.apks/.xapk/.apkm — skipped" }
                        continue
                    }
                    importDir = File(File(cacheDir, "import"), UUID.randomUUID().toString()).apply { mkdirs() }
                    val file = File(importDir, name)
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not open $name")
                    input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
                    batch += file to name
                } catch (e: Exception) {
                    importDir?.deleteRecursively()
                    runOnUiThread { lines += "[x] import failed: ${e.message}" }
                }
            }
            runOnUiThread {
                batch.forEach { (file, name) ->
                    enqueueDecompile(
                        file.path, name, ProjectLayout.slugify(name), emptyList(), temp = true,
                        output = root, batchId = batchId, batchSize = batch.size,
                    )
                }
            }
        }
    }

    private fun runInstalled(apps: List<ApplicationInfo>) {
        val root = Storage.projectsRoot(this)
        val batchId = UUID.randomUUID().toString()
        apps.forEach { app ->
            val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
            enqueueDecompile(
                app.sourceDir, app.packageName, app.packageName, paths,
                output = root, batchId = batchId, batchSize = apps.size,
            )
            lines += "[*] ${app.packageName}: ${paths.size} APK(s)"
        }
    }

    private fun enqueueDecompile(
        source: String,
        label: String,
        slug: String,
        splits: List<String>,
        temp: Boolean = false,
        output: File = Storage.projectsRoot(this),
        batchId: String = "",
        batchSize: Int = 1,
    ) {
        send(ForgeService.ENQUEUE, Bundle().apply {
            putString("id", UUID.randomUUID().toString())
            putString("kind", ForgeJob.DECOMPILE)
            putString("src", source)
            putString("out", output.path)
            putString("slug", slug)
            putString("label", label)
            putStringArrayList("splits", ArrayList(splits))
            putBoolean("temp", temp)
            putString("batchId", batchId)
            putInt("batchSize", batchSize)
            putBoolean("apktool", true)
            putBoolean("jadx", true)
            putBoolean("deobf", false)
            putBoolean("merge", true)
            putInt("threads", Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
            putBoolean("force", false)
        })
    }

    private fun runRebuild() {
        val project = lastProject ?: return
        send(ForgeService.ENQUEUE, Bundle().apply {
            putString("id", UUID.randomUUID().toString())
            putString("kind", ForgeJob.REBUILD)
            putString("project", project.path)
            putString("label", "Rebuild ${project.name}")
        })
    }

    private fun install(apk: File) {
        android.app.AlertDialog.Builder(this)
            .setMessage("The rebuilt APK has a different signature. Uninstall the original app before installing it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> beginInstall(apk) }
            .show()
    }

    private fun beginInstall(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            ))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    companion object {
        const val EXTRA_PROJECT = "project"
    }
}
