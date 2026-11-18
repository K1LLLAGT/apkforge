package com.gt.apkforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.gt.apkforge.engine.Forge
import com.gt.apkforge.engine.ForgeLog
import com.gt.apkforge.engine.Rebuilder
import com.gt.apkforge.engine.Stage
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs decompile/rebuild in its own process (`:forge`, large heap) so jadx can't take the UI
 * down with an OutOfMemoryError. Talks to the UI over a Messenger.
 */
class ForgeService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })
    private var running = false

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ApkForge jobs", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setContentTitle("ApkForge working…")
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 1, n, type)
        return START_NOT_STICKY
    }

    private fun handle(m: Message) {
        val reply = m.replyTo ?: return
        if (running) return send(reply, R_DONE, Bundle().apply { putString("error", "a job is already running") })
        running = true
        startForegroundService(Intent(this, ForgeService::class.java))
        val d = m.data
        thread(name = "forge-job") {
            val out = Bundle()
            try {
                val log = remoteLog(reply)
                when (m.what) {
                    DECOMPILE -> {
                        val paths = d.getStringArrayList("splits").orEmpty().map(::File)
                        val r = Forge().decompile(
                            File(d.getString("src")!!), File(d.getString("out")!!), log,
                            slug = d.getString("slug") ?: com.gt.apkforge.engine.ProjectLayout.slugify(File(d.getString("src")!!).name),
                            extraSplits = paths, sourceLabel = d.getString("label") ?: d.getString("src")!!)
                        out.putString("project", r.dir.path)
                    }
                    REBUILD -> {
                        val p = File(d.getString("project")!!)
                        out.putString("project", p.path)
                        out.putString("apk", Rebuilder(log).rebuild(p).path)
                    }
                }
            } catch (t: Throwable) {
                out.putString("error", "${t.javaClass.simpleName}: ${t.message}\n" +
                    ForgeLog.stackTrace(t).lineSequence().take(15).joinToString("\n"))
            }
            out.putString("heap", "heap max ${Runtime.getRuntime().maxMemory() shr 20} MB")
            Handler(Looper.getMainLooper()).post {
                running = false
                send(reply, R_DONE, out)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun remoteLog(to: Messenger) = object : ForgeLog {
        override fun info(msg: String) = send(to, R_LINE, Bundle().apply { putString("t", "[*] $msg") })
        override fun ok(msg: String) = send(to, R_LINE, Bundle().apply { putString("t", "[+] $msg") })
        override fun warn(msg: String) = send(to, R_LINE, Bundle().apply { putString("t", "[!] $msg") })
        override fun progress(stage: Stage, fraction: Float?) = send(to, R_STAGE, Bundle().apply {
            putString("t", stage.label + (fraction?.let { " ${(it * 100).toInt()}%" } ?: ""))
        })
    }

    private fun send(to: Messenger, what: Int, data: Bundle) {
        try { to.send(Message.obtain().also { it.what = what; it.data = data }) } catch (_: RemoteException) {}
    }

    companion object {
        const val DECOMPILE = 1
        const val REBUILD = 2
        const val R_LINE = 10
        const val R_STAGE = 11
        const val R_DONE = 12
        private const val CHANNEL = "jobs"
    }
}
