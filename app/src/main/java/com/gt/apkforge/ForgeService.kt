package com.gt.apkforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.core.app.ServiceCompat
import com.gt.apkforge.engine.DecompileOptions
import com.gt.apkforge.engine.Forge
import com.gt.apkforge.engine.ForgeLog
import com.gt.apkforge.engine.JadxOptions
import com.gt.apkforge.engine.Rebuilder
import com.gt.apkforge.engine.ResultStatus
import com.gt.apkforge.engine.Stage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/** Persistent FIFO worker, isolated in `:forge` so jadx cannot take down the UI process. */
class ForgeService : Service() {
    private val lock = Any()
    private val subscribers = LinkedHashSet<Messenger>()
    private val jobs = mutableListOf<ForgeJob>()
    private val queue = ArrayDeque<ForgeJob>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var currentId: String? = null
    private lateinit var jobsFile: File

    override fun onCreate() {
        super.onCreate()
        jobsFile = File(filesDir, "jobs.json")
        loadJobs()
    }

    override fun onBind(intent: Intent?): IBinder {
        if (synchronized(lock) { queue.isNotEmpty() }) startServiceSafely()
        return messenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val cancelId = intent?.takeIf { it.action == ACTION_CANCEL }?.getStringExtra(EXTRA_JOB_ID)
        if (cancelId != null) cancelJob(cancelId)
        val current = synchronized(lock) { currentId?.let(::findJob) }
        showForeground(current)
        startWorkerIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) { worker?.interrupt() }
        super.onDestroy()
    }

    private fun loadJobs() {
        if (!jobsFile.isFile) return
        try {
            val array = JSONObject(jobsFile.readText()).optJSONArray("jobs") ?: JSONArray()
            synchronized(lock) {
                for (i in 0 until array.length()) {
                    val job = ForgeJob.fromJson(array.getJSONObject(i))
                    if (job.status == ForgeJob.RUNNING) {
                        job.status = ForgeJob.FAILED
                        job.error = "interrupted"
                        job.endedAt = System.currentTimeMillis()
                        appendLog(job, "[x] interrupted: engine process died")
                        if (job.summaryLine.isEmpty()) job.summaryLine = "FAIL ${job.slug.ifEmpty { job.label }} (interrupted)"
                    }
                    jobs.add(job)
                    if (job.status == ForgeJob.QUEUED) queue.addLast(job)
                }
                trimHistory()
                persistLocked()
            }
        } catch (e: Exception) {
            throw IllegalStateException("Could not read persisted jobs from ${jobsFile.path}", e)
        }
    }

    private fun handle(message: Message) {
        val data = message.data
        when (message.what) {
            ENQUEUE -> enqueue(data)
            CANCEL -> data.getString("id")?.let(::cancelJob)
            SUBSCRIBE -> {
                message.replyTo?.let { subscriber ->
                    synchronized(lock) { subscribers.add(subscriber) }
                    sendSnapshot(subscriber)
                }
            }
            UNSUBSCRIBE -> message.replyTo?.let { synchronized(lock) { subscribers.remove(it) } }
        }
    }

    private fun enqueue(data: Bundle) {
        val kind = data.getString("kind") ?: return reportError("missing job kind")
        if (kind != ForgeJob.DECOMPILE && kind != ForgeJob.REBUILD) return reportError("invalid job kind: $kind")
        val source = data.getString("src").orEmpty()
        val projectDir = data.getString("project").orEmpty()
        if ((kind == ForgeJob.DECOMPILE && source.isEmpty()) || (kind == ForgeJob.REBUILD && projectDir.isEmpty())) {
            return reportError("missing job source")
        }
        val slug = data.getString("slug").orEmpty().ifEmpty {
            if (kind == ForgeJob.REBUILD) File(projectDir).name else com.gt.apkforge.engine.ProjectLayout.slugify(File(source).name)
        }
        val outRoot = data.getString("out").orEmpty()
        val job = ForgeJob(
            id = data.getString("id") ?: UUID.randomUUID().toString(),
            kind = kind,
            label = data.getString("label") ?: slug,
            source = source,
            splits = data.getStringArrayList("splits").orEmpty(),
            projectDir = if (kind == ForgeJob.DECOMPILE) File(outRoot, slug).path else projectDir,
            outputRoot = outRoot,
            slug = slug,
            temp = data.getBoolean("temp"),
            options = ForgeJobOptions(
                apktool = data.getBoolean("apktool", true),
                jadx = data.getBoolean("jadx", true),
                deobf = data.getBoolean("deobf"),
                merge = data.getBoolean("merge", true),
                threads = data.getInt("threads", Runtime.getRuntime().availableProcessors()).coerceIn(1, 8),
                force = data.getBoolean("force"),
            ),
            batchId = data.getString("batchId").orEmpty(),
            batchSize = data.getInt("batchSize", 1).coerceAtLeast(1),
        )
        var full = false
        synchronized(lock) {
            trimHistory()
            if (jobs.size >= MAX_JOBS) {
                full = true
            } else {
                jobs.add(job)
                queue.addLast(job)
                persistLocked()
            }
        }
        if (full) return reportError("job history is full; wait for a job to finish before adding more")
        broadcastUpdate(job)
        startServiceSafely()
    }

    private fun reportError(text: String) {
        val update = Bundle().apply { putString("error", text) }
        broadcast(R_ERROR, update)
    }

    private fun startServiceSafely() {
        val intent = Intent(this, ForgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun cancelJob(id: String) {
        var job: ForgeJob? = null
        var active: Thread? = null
        var deleteTemp: String? = null
        synchronized(lock) {
            job = findJob(id) ?: return
            when (job!!.status) {
                ForgeJob.QUEUED -> {
                    queue.remove(job)
                    job!!.status = ForgeJob.CANCELLED
                    job!!.endedAt = System.currentTimeMillis()
                    if (job!!.temp) deleteTemp = job!!.source
                }
                ForgeJob.RUNNING -> {
                    job!!.status = ForgeJob.CANCELLED
                    job!!.endedAt = System.currentTimeMillis()
                    active = worker
                }
                else -> return
            }
            appendLog(job!!, "[!] cancelled")
            job!!.error = "cancelled"
            job!!.summaryLine = "FAIL ${job!!.slug.ifEmpty { job!!.label }} (cancelled)"
            persistLocked()
        }
        deleteTemp?.let(::removeImportedFile)
        active?.interrupt()
        job?.let(::broadcastUpdate)
        updateForeground()
        job?.let(::writeBatchSummaryIfComplete)
        startWorkerIfNeeded()
    }

    private fun startWorkerIfNeeded() {
        synchronized(lock) {
            if (worker != null || queue.isEmpty()) {
                if (worker == null && queue.isEmpty()) stopWhenIdle()
                return
            }
            val thread = thread(start = false, name = "forge-queue") { workerLoop() }
            worker = thread
            thread.start()
        }
    }

    private fun workerLoop() {
        while (true) {
            val job = synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) {
                    worker = null
                    currentId = null
                    null
                } else {
                    currentId = next.id
                    if (next.status == ForgeJob.CANCELLED) null else {
                        next.status = ForgeJob.RUNNING
                        next.startedAt = System.currentTimeMillis()
                        next.stage = "starting"
                        persistLocked()
                        next
                    }
                }
            }
            if (job == null) {
                if (synchronized(lock) { queue.isEmpty() }) {
                    main.post { stopWhenIdle() }
                    return
                }
                continue
            }
            broadcastUpdate(job)
            updateForeground()
            execute(job)
            Thread.interrupted()
            synchronized(lock) {
                if (currentId == job.id) currentId = null
            }
        }
    }

    private fun execute(job: ForgeJob) {
        var error: String? = null
        var existingCompletedProject = false
        try {
            when (job.kind) {
                ForgeJob.DECOMPILE -> {
                    existingCompletedProject =
                        File(File(job.outputRoot, job.slug), ".done").isFile && !job.options.force
                    val decompileOptions = DecompileOptions(
                        apktool = job.options.apktool,
                        jadx = job.options.jadx,
                        merge = job.options.merge,
                        force = job.options.force,
                        jadxOptions = JadxOptions(threads = job.options.threads, deobf = job.options.deobf),
                        threads = job.options.threads,
                    )
                    val result = Forge(decompileOptions).decompile(
                        File(job.source),
                        File(job.outputRoot),
                        remoteLog(job),
                        slug = job.slug,
                        extraSplits = job.splits.map(::File),
                        sourceLabel = job.label,
                    )
                    synchronized(lock) {
                        job.projectDir = result.dir.path
                        job.summaryLine = result.summaryLine
                    }
                    if (result.status == ResultStatus.FAIL) error = result.error ?: "decompile failed"
                }
                ForgeJob.REBUILD -> {
                    val apk = Rebuilder(remoteLog(job)).rebuild(File(job.projectDir))
                    synchronized(lock) {
                        job.apk = apk.path
                        job.projectDir = File(job.projectDir).path
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            error = "${t.javaClass.simpleName}: ${t.message ?: "no details"}"
            remoteLog(job).detail(ForgeLog.stackTrace(t))
        } finally {
            if (job.temp) removeImportedFile(job.source)
        }

        var cancelled = false
        synchronized(lock) {
            cancelled = job.status == ForgeJob.CANCELLED
            if (cancelled) {
                if (job.kind == ForgeJob.DECOMPILE && job.options.jadx && !existingCompletedProject) File(job.projectDir.ifEmpty {
                    File(job.outputRoot, job.slug).path
                }).deleteRecursively()
            } else {
                job.endedAt = System.currentTimeMillis()
                if (error == null) {
                    job.status = ForgeJob.DONE
                } else {
                    job.status = ForgeJob.FAILED
                    job.error = error ?: "unknown error"
                    appendLog(job, "[x] $error")
                    if (job.summaryLine.isEmpty()) job.summaryLine = "FAIL ${job.slug.ifEmpty { job.label }} ($error)"
                }
            }
            persistLocked()
        }
        if (!cancelled) {
            writeBatchSummaryIfComplete(job)
            postCompletion(job)
        }
        broadcastUpdate(job)
        updateForeground()
    }

    private fun remoteLog(job: ForgeJob) = object : ForgeLog {
        override fun info(msg: String) = addLog(job, "[*] $msg")
        override fun ok(msg: String) = addLog(job, "[+] $msg")
        override fun warn(msg: String) = addLog(job, "[!] $msg")
        override fun detail(msg: String) = msg.lineSequence().forEach { addLog(job, it) }
        override fun progress(stage: Stage, fraction: Float?) {
            synchronized(lock) {
                if (job.status == ForgeJob.CANCELLED) return
                job.stage = stage.label
                job.fraction = fraction?.coerceIn(0f, 1f)
                persistLocked()
            }
            broadcast(R_STAGE, Bundle().apply {
                putString("id", job.id)
                putString("stage", stage.label)
                fraction?.let { putFloat("fraction", it) }
            })
            updateForeground()
        }
    }

    private fun addLog(job: ForgeJob, text: String) {
        synchronized(lock) {
            if (job.status == ForgeJob.CANCELLED) return
            appendLog(job, text)
            persistLocked()
        }
        broadcast(R_LOG_LINE, Bundle().apply { putString("id", job.id); putString("text", text) })
    }

    private fun appendLog(job: ForgeJob, text: String) {
        job.logLines.add(text)
        while (job.logLines.size > 200) job.logLines.removeAt(0)
    }

    private fun removeImportedFile(path: String) {
        val source = File(path)
        val importRoot = File(cacheDir, "import")
        val jobDir = source.parentFile
        if (jobDir?.parentFile == importRoot && jobDir.exists() && !jobDir.deleteRecursively()) {
            android.util.Log.w("ApkForge", "Could not remove temporary import ${jobDir.path}")
        }
    }

    private fun writeBatchSummaryIfComplete(job: ForgeJob) {
        if (job.batchId.isEmpty() || job.kind != ForgeJob.DECOMPILE || job.outputRoot.isEmpty()) return
        val batch = synchronized(lock) {
            jobs.filter { it.batchId == job.batchId && it.kind == ForgeJob.DECOMPILE }
                .takeIf { it.size >= job.batchSize && it.take(job.batchSize).all { item -> item.status in TERMINAL } }
                ?.take(job.batchSize)
                ?.map { it.summaryLine }
        } ?: return
        File(job.outputRoot, "summary.txt").writeText(batch.joinToString("\n", postfix = "\n"))
    }

    private fun postCompletion(job: ForgeJob) {
        if (job.status != ForgeJob.DONE && job.status != ForgeJob.FAILED) return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_PROJECT, job.projectDir)
        }
        val pending = PendingIntent.getActivity(this, job.id.hashCode(), intent, pendingFlags())
        val notification = Notification.Builder(this, COMPLETE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (job.status == ForgeJob.DONE) "ApkForge finished" else "ApkForge failed")
            .setContentText(job.label)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(1000 + (job.id.hashCode() and 0x3fffffff), notification)
    }

    private fun showForeground(job: ForgeJob?) {
        val notification = buildForeground(job)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, FOREGROUND_ID, notification, type)
    }

    private fun updateForeground() {
        val active = synchronized(lock) { currentId?.let(::findJob) }
        if (active == null) return
        getSystemService(NotificationManager::class.java).notify(FOREGROUND_ID, buildForeground(active))
    }

    private fun buildForeground(job: ForgeJob?): Notification {
        val queuedCount = synchronized(lock) { queue.count { it.status == ForgeJob.QUEUED } }
        val label = job?.label ?: "Waiting for jobs"
        val stage = job?.stage?.takeIf { it.isNotEmpty() } ?: "queued"
        val text = "$stage · $queuedCount queued"
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(label)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        job?.fraction?.let { builder.setProgress(100, (it * 100).toInt(), false) }
            ?: builder.setProgress(0, 0, true)
        job?.let {
            val cancel = Intent(this, ForgeService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, it.id)
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                PendingIntent.getService(this, it.id.hashCode(), cancel, pendingFlags()),
            )
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "ApkForge jobs", NotificationManager.IMPORTANCE_LOW),
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(COMPLETE_CHANNEL, "ApkForge job results", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun stopWhenIdle() {
        if (synchronized(lock) { worker != null || queue.isNotEmpty() }) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendSnapshot(to: Messenger) {
        val snapshot = synchronized(lock) { jobs.map { it.toJson().toString() } }
        snapshot.forEach { send(to, R_JOB_UPDATE, Bundle().apply { putString("job", it) }) }
    }

    private fun broadcastUpdate(job: ForgeJob) {
        val json = synchronized(lock) { job.toJson().toString() }
        broadcast(R_JOB_UPDATE, Bundle().apply { putString("job", json) })
    }

    private fun broadcast(what: Int, data: Bundle) {
        val targets = synchronized(lock) { subscribers.toList() }
        targets.forEach { subscriber ->
            try {
                subscriber.send(Message.obtain().also { it.what = what; it.data = data })
            } catch (_: RemoteException) {
                synchronized(lock) { subscribers.remove(subscriber) }
            }
        }
    }

    private fun send(to: Messenger, what: Int, data: Bundle) {
        try {
            to.send(Message.obtain().also { it.what = what; it.data = data })
        } catch (_: RemoteException) {
            synchronized(lock) { subscribers.remove(to) }
        }
    }

    private fun persistLocked() {
        trimHistory()
        jobsFile.parentFile?.mkdirs()
        val temporary = File(jobsFile.parentFile, "jobs.json.tmp")
        temporary.writeText(JSONObject().put("jobs", JSONArray().also { array ->
            jobs.forEach { array.put(it.toJson()) }
        }).toString())
        if (!temporary.renameTo(jobsFile)) {
            temporary.delete()
            throw IOException("Could not replace ${jobsFile.path}")
        }
    }

    private fun trimHistory() {
        while (jobs.size > MAX_JOBS) {
            val removable = jobs.indexOfFirst { it.status in TERMINAL }
            if (removable < 0) break
            jobs.removeAt(removable)
        }
    }

    private fun findJob(id: String): ForgeJob? = jobs.firstOrNull { it.id == id }

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return flags
    }

    companion object {
        const val ENQUEUE = 1
        const val CANCEL = 2
        const val SUBSCRIBE = 3
        const val UNSUBSCRIBE = 4
        const val R_JOB_UPDATE = 10
        const val R_LOG_LINE = 11
        const val R_STAGE = 12
        const val R_ERROR = 13
        const val EXTRA_JOB_ID = "job_id"
        private const val ACTION_CANCEL = "com.gt.apkforge.CANCEL_JOB"
        private const val CHANNEL = "jobs"
        private const val COMPLETE_CHANNEL = "job-results"
        private const val FOREGROUND_ID = 1
        private const val MAX_JOBS = 100
        private val TERMINAL = setOf(ForgeJob.DONE, ForgeJob.FAILED, ForgeJob.CANCELLED)
    }
}
