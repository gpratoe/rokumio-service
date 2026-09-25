package com.rokumio.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * Runs the standalone Stremio server as a foreground service so the app's
 * process isn't killed while the server is meant to be running, and holds a
 * partial wake lock so the CPU stays awake (required for the server to keep
 * serving with the screen off).
 *
 * The Node runtime is a self-contained standalone executable (Node 26) shipped
 * as a bundled native executable (libnode.so, extracted to nativeLibraryDir at
 * install) and spawn()ed like ffmpeg/ffprobe. It runs its own process, so this
 * service just launches it, forwards its stdout/stderr to a log file for
 * debugging, and destroys the process when the service is stopped.
 *
 * Shutdown semantics: because Node is a separate spawned process (not embedded
 * in ours), we can stop it cleanly by destroying the process. We do NOT attempt
 * to keep a faithful in-process restart; each launch starts a fresh Node.
 */
class ServerService : Service() {

    private val channelId = "server"
    companion object {
        /** Notification "Stop" action; also used to tear down on task removal. */
        const val ACTION_STOP = "com.rokumio.host.action.STOP_SERVER"

        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()
        private val _address = MutableStateFlow<String?>(null)
        val address = _address.asStateFlow()
    }
    private val notifyId = 1

    private lateinit var locator: ServerLocator
    private var wakeLock: PowerManager.WakeLock? = null
    private var nodeProcess: Process? = null

    /** Set once a shutdown is requested so the spawn thread doesn't keep going. */
    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        locator = ServerLocator(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rokumio:server")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Tapped the notification's Stop button: tear the server down. The
        // service is already foreground, so don't re-enter the start path.
        if (intent?.action == ACTION_STOP) {
            shutdownServer()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        // Starting Node (locating binaries, spawning, first-boot work) cannot
        // block the main thread.
        Thread {
            try {
                locator.prepareServerData()
                locator.writePreload()
                if (!locator.hasServerJs()) {
                    throw IllegalStateException("server.js not imported")
                }
                if (!locator.nodeBinary().isFile) {
                    throw IllegalStateException("node binary not found: ${locator.nodeBinary().absolutePath}")
                }

                val nodeLog = File(locator.filesDir(), "node.log")
                val proc = spawnNode(nodeLog)
                if (stopping) {
                    proc.destroy()
                    return@Thread
                }
                nodeProcess = proc
                _running.value = true
                discoverAddress()
                updateNotificationRunning()
                // Wait for the child to exit. When it does, the service has
                // nothing left to do.
                nodeProcess?.waitFor()
                _address.value = null
                _running.value = false
                stopSelf()
            } catch (e: Exception) {
                e.printStackTrace()
                _running.value = false
                stopSelf()
            }
        }.apply {
            isDaemon = false
            name = "server-node"
        }.start()
        return START_STICKY
    }

    /**
     * The user removed the app from the task switcher (closed it, not merely
     * hid it). The server is tied to the app's lifetime, so shut it all down.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        shutdownServer()
    }

    /** Kill Node, drop the foreground notification, and stop the service. */
    private fun shutdownServer() {
        stopping = true
        _running.value = false
        _address.value = null
        try {
            nodeProcess?.destroy()
            nodeProcess?.waitFor()
        } catch (e: Exception) {
            nodeProcess = null
        }
        nodeProcess = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Spawn the standalone Node executable with `node -r <preload> <server.js>`.
     * The preload sets APP_PATH/HOME/FFMPEG_BIN/FFPROBE_BIN/HLS_DEBUG before the
     * server runs. stdout/stderr are merged and tee'd both to a log file and to
     * logcat so the server's output is easy to inspect on-device.
     */
    private fun spawnNode(logFile: File): Process {
        val command = arrayOf(
            locator.nodeBinary().absolutePath,
            "-r",
            File(locator.filesDir(), "preload.js").absolutePath,
            locator.serverJs().absolutePath
        )
        android.util.Log.i("ServerService", "spawning node: " + command.joinToString(" "))
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        TeeStream(process.inputStream, logFile, "node.log").start()
        return process
    }

    private val fetchAttempts = 10
    private val fetchDelayMs = 500L

    /** Probe /settings until the server reports its baseUrl, then stop. */
    private fun discoverAddress() {
        for (attempt in 1..fetchAttempts) {
            val baseUrl = locator.fetchBaseUrl()
            if (baseUrl != null) {
                _address.value = baseUrl
                return
            }
            try {
                Thread.sleep(fetchDelayMs)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    override fun onDestroy() {
        // Kill the spawned Node process if still running.
        nodeProcess?.destroy()
        nodeProcess?.waitFor()
        _address.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        // dataSync type must match the manifest declaration. ServiceCompat
        // handles the API<29 case where the 3-arg startForeground doesn't exist.
        ServiceCompat.startForeground(
            this,
            notifyId,
            notification(running = false, address = null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /** Refresh the notification once the server reports its address. */
    private fun updateNotificationRunning() {
        val address = _address.value ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notifyId, notification(running = true, address = address))
    }

    /** The foreground notification: "starting" or "running + Stop action". */
    private fun notification(running: Boolean, address: String?): Notification {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent())
        if (running && address != null) {
            builder
                .setContentTitle(getString(R.string.notif_title_running))
                .setContentText(getString(R.string.notif_body_addr, address))
                .addAction(
                    R.drawable.ic_stop,
                    getString(R.string.notif_stop),
                    stopPendingIntent()
                )
        } else {
            builder
                .setContentTitle(getString(R.string.notif_title_starting))
                .setContentText(getString(R.string.notif_body_starting))
        }
        return builder.build()
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun stopPendingIntent(): PendingIntent {
        val stop = Intent(this, ServerService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this, 0, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Server",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }
}

/**
 * Reads Node's merged stdout/stderr line by line, writing each line both to a
 * log file (child of filesDir) and to logcat so the server output is easy to
 * inspect on-device.
 */
private class TeeStream(
    input: java.io.InputStream,
    private val file: File,
    private val tag: String
) : Thread("node-tee") {
    private val reader = BufferedReader(InputStreamReader(input))

    init {
        isDaemon = true
    }

    override fun run() {
        val out = FileOutputStream(file)
        try {
            var line = reader.readLine()
            while (line != null) {
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                android.util.Log.i(tag, line)
                line = reader.readLine()
            }
        } catch (e: Exception) {
            // Log stream closed; nothing to do.
        } finally {
            try {
                out.flush()
                out.close()
            } catch (e: Exception) {
                // Ignore.
            }
        }
    }
}
