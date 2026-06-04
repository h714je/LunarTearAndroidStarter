package com.example.lunartearlauncher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File

class MainActivity : Activity(), LauncherUi.Host, AssetsImporter.Listener {
    private val pickAssetsFolderRequest = 1001
    private val pickAssetsTarRequest = 1002
    private val createDbZipRequest = 1003
    private val pickDbZipRequest = 1004

    private lateinit var ui: LauncherUi
    private lateinit var assetsImporter: AssetsImporter
    private lateinit var dbBackup: DbBackup

    private var ipMode = IpMode.LOCAL
    private var manualIpValue = ""
    private var startRequestedAt = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private var autoRefreshUntil: Long = 0L
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() <= autoRefreshUntil) {
                refreshStatus()
                uiHandler.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assetsImporter = AssetsImporter(this, this)
        dbBackup = DbBackup(this)
        requestNotificationsIfNeeded()
        ui = LauncherUi(this, this)
        ui.buildShell()
        ui.showScreen(Screen.HOME)
    }

    override fun onResume() {
        super.onResume()
        if (::ui.isInitialized) ui.showNotice(readinessText())
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(autoRefreshRunnable)
        super.onDestroy()
    }

    override fun currentIpMode(): IpMode = ipMode

    override fun currentManualIp(): String = manualIpValue

    override fun onIpModeChanged(mode: IpMode) {
        ipMode = mode
    }

    override fun onManualIpChanged(value: String) {
        manualIpValue = value.trim()
    }

    override fun onPickAssetsFolder() = pickAssetsFolder()

    override fun onPickAssetsTar() = pickAssetsTar()

    override fun onCreateDbZip() = createDbZip()

    override fun onPickDbZip() = pickDbZip()

    override fun onStartServer(mode: IpMode, manualIp: String) {
        ipMode = mode
        manualIpValue = manualIp.trim()
        startServer()
    }

    override fun onStopServer() = stopServer()

    override fun onRefresh() = refreshStatus()

    override fun onClearLogs() = clearLogs()

    override fun onAssetImportMessage(text: String) {
        runOnUiThread { ui.showNotice(text) }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    private fun pickAssetsFolder() {
        if (assetsImporter.isRunning()) {
            ui.showNotice("Assets import is already running.\n${assetsImporter.statusText()}")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickAssetsFolderRequest)
    }

    private fun pickAssetsTar() {
        if (assetsImporter.isRunning()) {
            ui.showNotice("Assets import is already running.\n${assetsImporter.statusText()}")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickAssetsTarRequest)
    }

    private fun createDbZip() {
        val dbDir = File(filesDir, "server/db")
        if (!dbDir.isDirectory || dbDir.listFiles().isNullOrEmpty()) {
            ui.showNotice("DB folder is empty. Start the server once before export.")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "lunar-tear-db.zip")
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivityForResult(intent, createDbZipRequest)
    }

    private fun pickDbZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, pickDbZipRequest)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        if (requestCode == pickAssetsFolderRequest || requestCode == pickAssetsTarRequest) {
            takeReadPermissionIfPossible(uri)
        }

        when (requestCode) {
            pickAssetsFolderRequest -> importAssetsFolderAsync(uri)
            pickAssetsTarRequest -> importAssetsTarAsync(uri)
            createDbZipRequest -> exportDbZipAsync(uri)
            pickDbZipRequest -> importDbZipAsync(uri)
        }
    }

    private fun takeReadPermissionIfPossible(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Some providers do not support persistable permissions. One-time import still works.
        }
    }

    private fun importAssetsFolderAsync(uri: Uri) {
        assetsImporter.begin("Preparing folder import", -1L)
        Thread {
            try {
                assetsImporter.importAssetsFolder(uri)
                assetsImporter.finish("Assets imported from folder. ${assetStorageStateText()}")
            } catch (e: Exception) {
                assetsImporter.fail("Folder import failed: ${e.message}")
            }
        }.start()
    }

    private fun importAssetsTarAsync(uri: Uri) {
        assetsImporter.begin("Preparing assets.tar import", assetsImporter.documentSize(uri))
        Thread {
            try {
                assetsImporter.importAssetsTar(uri)
                assetsImporter.finish("assets.tar imported. ${assetStorageStateText()}")
            } catch (e: Exception) {
                assetsImporter.fail("Tar import failed: ${e.message}")
            }
        }.start()
    }

    private fun exportDbZipAsync(uri: Uri) {
        ui.showNotice("Exporting db.zip...")
        Thread {
            try {
                dbBackup.exportDbZip(uri)
                runOnUiThread { ui.showNotice("DB exported to db.zip. ${dbStateText()}") }
            } catch (e: Exception) {
                runOnUiThread { ui.showNotice("DB export failed: ${e.message}") }
            }
        }.start()
    }

    private fun importDbZipAsync(uri: Uri) {
        ui.showNotice("Importing db.zip...")
        Thread {
            try {
                stopService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP))
                dbBackup.importDbZip(uri)
                runOnUiThread { ui.showNotice("DB imported. ${dbStateText()}") }
            } catch (e: Exception) {
                runOnUiThread { ui.showNotice("DB import failed: ${e.message}") }
            }
        }.start()
    }

    private fun startServer() {
        if (!assetsLookOk()) {
            ui.showNotice("Assets are missing. Import assets first.\n${assetStorageStateText()}")
            return
        }
        val host = when (ipMode) {
            IpMode.LOCAL -> "127.0.0.1"
            IpMode.LAN -> WifiIp.find(this) ?: "127.0.0.1"
            IpMode.MANUAL -> manualIpValue.ifBlank { "127.0.0.1" }
        }
        val intent = Intent(this, ServerService::class.java)
            .setAction(ServerService.ACTION_START)
            .putExtra(ServerService.EXTRA_HOST, host)
        startRequestedAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        ui.showNotice("Starting server for $host...\nREADY requires auth-server, lunar-tear gRPC and octo-cdn to report listening.")
        startAutoRefresh()
    }

    private fun stopServer() {
        startService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP))
        startRequestedAt = 0L
        ui.showNotice("Stopping server...")
        uiHandler.removeCallbacks(autoRefreshRunnable)
    }

    private fun startAutoRefresh() {
        autoRefreshUntil = System.currentTimeMillis() + 10 * 60 * 1000L
        uiHandler.removeCallbacks(autoRefreshRunnable)
        uiHandler.postDelayed(autoRefreshRunnable, 1500)
    }

    private fun refreshStatus() {
        ui.showNotice(readinessText())
    }

    private fun clearLogs() {
        try {
            File(filesDir, "server/launcher.log").writeText("")
            ui.showNotice("Log cleared.")
        } catch (e: Exception) {
            ui.showNotice("Clear logs failed: ${e.message}")
        }
    }

    override fun logTailText(): String {
        val log = File(filesDir, "server/launcher.log")
        return if (log.exists()) {
            val tail = log.readLines().takeLast(160).joinToString("\n")
            if (tail.isBlank()) "Log is empty." else tail
        } else {
            "No log yet. Start the server or import assets first."
        }
    }

    override fun readinessText(): String {
        val readyFile = File(filesDir, "server/ready.state")
        val serviceState = serviceListeningStateSinceStart()
        val services = when {
            readyFile.isFile -> "Services: auth-server OK, lunar-tear gRPC OK, octo-cdn OK"
            startRequestedAt > 0L -> "Services: ${serviceState.longText()}"
            else -> "Services: not started"
        }
        return "Status: ${statusValueText()}\n$services\n${assetStorageStateText()}\n${dbStateText()}"
    }

    override fun statusValueText(): String {
        val readyFile = File(filesDir, "server/ready.state")
        val serviceState = serviceListeningStateSinceStart()
        val startingWindow = startRequestedAt > 0L && System.currentTimeMillis() - startRequestedAt < 10 * 60 * 1000L
        return when {
            assetsImporter.isRunning() -> "Importing assets"
            readyFile.isFile || (startRequestedAt > 0L && serviceState.isReady) -> "Ready"
            startingWindow -> {
                val suffix = if (File(filesDir, "server/assets.tar").isFile && !serviceState.cdn) {
                    " - CDN may be indexing assets.tar"
                } else {
                    ""
                }
                "Starting: ${serviceState.shortText()}$suffix"
            }
            assetsLookOk() -> "Ready to start"
            else -> "Needs assets"
        }
    }

    private data class ServiceListeningState(
        val auth: Boolean,
        val grpc: Boolean,
        val cdn: Boolean
    ) {
        val isReady: Boolean get() = auth && grpc && cdn

        fun shortText(): String {
            val authText = if (auth) "auth OK" else "auth wait"
            val grpcText = if (grpc) "gRPC OK" else "gRPC wait"
            val cdnText = if (cdn) "CDN OK" else "CDN wait"
            return "$authText, $grpcText, $cdnText"
        }

        fun longText(): String {
            val authText = if (auth) "auth-server OK" else "auth-server waiting for listening line"
            val grpcText = if (grpc) "lunar-tear gRPC OK" else "lunar-tear waiting for gRPC listening line"
            val cdnText = if (cdn) "octo-cdn OK" else "octo-cdn waiting for listening line"
            return "$authText, $grpcText, $cdnText"
        }
    }

    private fun serviceListeningStateSinceStart(): ServiceListeningState {
        val log = File(filesDir, "server/launcher.log")
        if (!log.isFile) return ServiceListeningState(auth = false, grpc = false, cdn = false)

        var auth = false
        var grpc = false
        var cdn = false
        val cutoff = startRequestedAt

        try {
            log.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (cutoff > 0L) {
                        val millis = line.substringBefore(' ').toLongOrNull()
                        if (millis != null && millis < cutoff) return@forEach
                    }
                    val lower = line.lowercase()
                    if (lower.contains("auth server listening on")) auth = true
                    if (lower.contains("grpc server listening on")) grpc = true
                    if (lower.contains("octo cdn listening on")) cdn = true
                }
            }
        } catch (_: Exception) {
            return ServiceListeningState(auth = false, grpc = false, cdn = false)
        }

        return ServiceListeningState(auth = auth, grpc = grpc, cdn = cdn)
    }

    override fun assetStateValueText(): String {
        return if (assetsImporter.isRunning()) {
            assetsImporter.statusText().replace("\n", " | ")
        } else {
            assetStorageStateValueText()
        }
    }

    override fun assetImportStatusText(): String = assetsImporter.statusText()

    override fun dbStateValueText(): String {
        val dbDir = File(filesDir, "server/db")
        val files = dbDir.walkTopDown().filter { it.isFile }.toList()
        val size = files.sumOf { it.length() }
        return if (files.isEmpty()) {
            "empty"
        } else {
            "${files.size} file(s), ${humanSize(size)}"
        }
    }

    override fun ipModeLabel(): String {
        return when (ipMode) {
            IpMode.LOCAL -> "127.0.0.1"
            IpMode.LAN -> WifiIp.find(this) ?: "LAN IP not found"
            IpMode.MANUAL -> manualIpValue.ifBlank { "manual" }
        }
    }

    private fun assetsLookOk(): Boolean {
        val master = File(filesDir, "server/assets/release/20240404193219.bin.e")
        val tar = File(filesDir, "server/assets.tar")
        return master.isFile && (tar.isFile || File(filesDir, "server/assets/revisions").isDirectory)
    }

    private fun assetStorageStateText(): String {
        return "Assets: ${assetStorageStateValueText()}"
    }

    private fun assetStorageStateValueText(): String {
        val master = File(filesDir, "server/assets/release/20240404193219.bin.e")
        val tar = File(filesDir, "server/assets.tar")
        return when {
            tar.isFile && master.isFile -> "OK, direct tar mode"
            master.isFile -> "OK, folder mode"
            else -> "missing"
        }
    }

    private fun dbStateText(): String {
        return "DB: ${dbStateValueText()}"
    }
}
