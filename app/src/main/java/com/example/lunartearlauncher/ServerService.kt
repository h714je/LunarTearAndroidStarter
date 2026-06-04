package com.example.lunartearlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File
import java.security.SecureRandom

class ServerService : Service() {
    companion object {
        const val ACTION_START = "com.example.lunartearlauncher.START"
        const val ACTION_STOP = "com.example.lunartearlauncher.STOP"
        const val EXTRA_HOST = "host"
        private const val CHANNEL_ID = "lunar_tear_server"
    }

    private val processes = mutableListOf<Process>()
    private lateinit var logFile: File
    private lateinit var readyFile: File
    private val readinessLock = Any()
    private val readyComponents = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val serverDir = File(filesDir, "server")
        serverDir.mkdirs()
        logFile = File(serverDir, "launcher.log")
        readyFile = File(serverDir, "ready.state")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                startForeground(1, notification("Starting Lunar Tear server"))
                Thread { startAll(host) }.start()
            }
            ACTION_STOP -> stopAllAndSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun startAll(host: String) {
        try {
            if (processes.isNotEmpty()) {
                log("Already running")
                return
            }

            readyFile.delete()
            resetReadiness()

            val serverDir = File(filesDir, "server")
            val dbDir = File(serverDir, "db").apply { mkdirs() }
            val assetsMarker = File(serverDir, "assets/release/20240404193219.bin.e")
            val assetsTar = File(serverDir, "assets.tar")
            val assetsFolderRevisions = File(serverDir, "assets/revisions")
            if (!assetsMarker.isFile || (!assetsTar.isFile && !assetsFolderRevisions.isDirectory)) {
                log("Assets missing. Need assets.tar plus ${assetsMarker.absolutePath}, or extracted assets folder")
                stopSelf()
                return
            }

            val bindHost = if (host == "127.0.0.1") "127.0.0.1" else "0.0.0.0"
            val authPort = 3000
            val cdnPort = 8080
            val grpcPort = 8003
            val nativeDir = applicationInfo.nativeLibraryDir
            val secret = getOrCreateSecret()

            log("Starting with host=$host bind=$bindHost")
            log("Server dir: ${serverDir.absolutePath}")
            log("Native dir: $nativeDir")
            updateNotification("Starting auth, CDN and game server")

            startProcess(
                name = "auth-server",
                dir = serverDir,
                args = listOf(
                    "$nativeDir/libauth-server.so",
                    "--listen", "$bindHost:$authPort",
                    "--db", File(dbDir, "auth.db").absolutePath,
                    "--secret", secret
                ),
                onLine = { line ->
                    if (looksLikeAuthListeningLine(line)) {
                        markComponentReady("auth-server", line)
                    }
                }
            )

            val directTarMode = assetsTar.isFile
            val octoArgs = mutableListOf(
                "$nativeDir/libocto-cdn.so",
                "--listen", "$bindHost:$cdnPort",
                "--public-addr", "$host:$cdnPort",
                "--assets-dir", serverDir.absolutePath
            )
            if (directTarMode) {
                octoArgs.add("--assets-tar")
                octoArgs.add(assetsTar.absolutePath)
                log("Using direct tar assets: ${assetsTar.absolutePath}")
                log("CDN may index assets.tar before listening. Wait for all services to report listening.")
                updateNotification("Waiting for server processes to listen")
            }

            startProcess(
                name = "octo-cdn",
                dir = serverDir,
                args = octoArgs,
                onLine = { line ->
                    if (looksLikeCdnListeningLine(line)) {
                        markComponentReady("octo-cdn", line)
                    }
                }
            )

            startProcess(
                name = "lunar-tear",
                dir = serverDir,
                args = listOf(
                    "$nativeDir/liblunar-tear.so",
                    "--listen", "$bindHost:$grpcPort",
                    "--public-addr", "$host:$grpcPort",
                    "--db", File(dbDir, "game.db").absolutePath,
                    "--octo-url", "http://$host:$cdnPort",
                    "--auth-url", "http://$host:$authPort"
                ),
                onLine = { line ->
                    if (looksLikeGrpcListeningLine(line)) {
                        markComponentReady("lunar-tear", line)
                    }
                }
            )

            log("Started. Game server: $host:$grpcPort, CDN: http://$host:$cdnPort, Auth: http://$host:$authPort")
        } catch (e: Exception) {
            log("Start failed: ${e.stackTraceToString()}")
            stopAllAndSelf()
        }
    }

    private fun startProcess(
        name: String,
        dir: File,
        args: List<String>,
        onLine: ((String) -> Unit)? = null
    ) {
        log("Launching $name")
        val process = ProcessBuilder(args)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        processes.add(process)
        Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    log("[$name] $line")
                    onLine?.invoke(line)
                }
            }
            val code = process.waitFor()
            log("$name exited with code $code")
            if (code != 0) {
                synchronized(readinessLock) { readyComponents.remove(name) }
                readyFile.delete()
            }
        }.start()
    }

    private fun looksLikeAuthListeningLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("auth server listening on")
    }

    private fun looksLikeGrpcListeningLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("grpc server listening on")
    }

    private fun looksLikeCdnListeningLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("octo cdn listening on")
    }

    private fun resetReadiness() {
        synchronized(readinessLock) {
            readyComponents.clear()
        }
    }

    private fun markComponentReady(component: String, line: String) {
        synchronized(readinessLock) {
            if (!readyComponents.add(component)) return
            log("READY-CHECK: $component listening: $line")
            updateNotification("Ready check: ${readyComponents.size}/3 services listening")

            if (readyComponents.contains("auth-server") &&
                readyComponents.contains("lunar-tear") &&
                readyComponents.contains("octo-cdn")
            ) {
                markReadyLocked("auth-server, lunar-tear and octo-cdn are listening")
            }
        }
    }

    private fun markReadyLocked(reason: String) {
        if (!readyFile.isFile) {
            readyFile.writeText("${System.currentTimeMillis()} $reason\n")
            log("READY: $reason")
            updateNotification("Lunar Tear server is READY")
        }
    }

    private fun stopAllAndSelf() {
        stopAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopAll() {
        log("Stopping ${processes.size} process(es)")
        processes.forEach { process ->
            try { process.destroy() } catch (_: Exception) {}
        }
        processes.clear()
        resetReadiness()
        try { readyFile.delete() } catch (_: Exception) {}
    }

    private fun getOrCreateSecret(): String {
        val prefs = getSharedPreferences("server", MODE_PRIVATE)
        val old = prefs.getString("secret", null)
        if (old != null) return old
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("secret", hex).apply()
        return hex
    }

    private fun log(message: String) {
        val line = "${System.currentTimeMillis()} $message\n"
        try { logFile.appendText(line) } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Lunar Tear Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(1, notification(text))
        } catch (_: Exception) {}
    }

    private fun notification(text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("Lunar Tear Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .build()
    }
}
