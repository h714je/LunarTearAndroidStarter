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
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private val pickAssetsFolderRequest = 1001
    private val pickAssetsTarRequest = 1002
    private val createDbZipRequest = 1003
    private val pickDbZipRequest = 1004

    private lateinit var status: TextView
    private lateinit var manualIp: EditText
    private lateinit var modeGroup: RadioGroup

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
        requestNotificationsIfNeeded()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 160)
        }

        root.addView(TextView(this).apply {
            text = "Lunar Tear Server"
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text = "1) Import assets.tar. 2) Pick IP mode. 3) Start server. Wait until status says READY."
            setPadding(0, 8, 0, 24)
        })

        root.addView(Button(this).apply {
            text = "Import assets folder"
            setOnClickListener { pickAssetsFolder() }
        })

        root.addView(Button(this).apply {
            text = "Import assets.tar (direct mode)"
            setOnClickListener { pickAssetsTar() }
        })

        root.addView(TextView(this).apply {
            text = "Database backup"
            setPadding(0, 24, 0, 8)
        })

        root.addView(Button(this).apply {
            text = "Export db.zip"
            setOnClickListener { createDbZip() }
        })

        root.addView(Button(this).apply {
            text = "Import db.zip"
            setOnClickListener { pickDbZip() }
        })

        root.addView(TextView(this).apply {
            text = "IP mode"
            setPadding(0, 24, 0, 8)
        })

        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val local = RadioButton(this).apply {
            id = View.generateViewId()
            text = "127.0.0.1 - client on this phone"
            isChecked = true
        }
        val lan = RadioButton(this).apply {
            id = View.generateViewId()
            text = "LAN IP - another device in same Wi-Fi"
        }
        val manual = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Manual IP"
        }
        modeGroup.addView(local)
        modeGroup.addView(lan)
        modeGroup.addView(manual)
        root.addView(modeGroup)

        manualIp = EditText(this).apply {
            hint = "Manual IP, for example 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        root.addView(manualIp)

        root.addView(Button(this).apply {
            text = "Start server"
            setOnClickListener { startServer(local.id, lan.id, manual.id) }
        })

        root.addView(Button(this).apply {
            text = "Stop server"
            setOnClickListener { stopServer() }
        })

        root.addView(Button(this).apply {
            text = "Refresh log"
            setOnClickListener { refreshStatus() }
        })

        root.addView(Button(this).apply {
            text = "Clear logs"
            setOnClickListener { clearLogs() }
        })

        status = TextView(this).apply {
            text = initialStatusText()
            setPadding(0, 24, 0, 0)
        }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(autoRefreshRunnable)
        super.onDestroy()
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    private fun pickAssetsFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickAssetsFolderRequest)
    }

    private fun pickAssetsTar() {
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
            status.text = "DB folder is empty. Start the server once before export."
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
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Some providers do not support persistable permissions. One-time import still works.
            }
        }

        when (requestCode) {
            pickAssetsFolderRequest -> {
                status.text = "Copying assets folder..."
                Thread {
                    try {
                        importAssetsFolder(uri)
                        runOnUiThread { status.text = "Assets imported from folder. ${assetStateText()}" }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "Folder import failed: ${e.message}" }
                    }
                }.start()
            }
            pickAssetsTarRequest -> {
                status.text = "Copying assets.tar..."
                Thread {
                    try {
                        importAssetsTar(uri)
                        runOnUiThread { status.text = "assets.tar imported. ${assetStateText()}" }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "Tar import failed: ${e.message}" }
                    }
                }.start()
            }
            createDbZipRequest -> {
                status.text = "Exporting db.zip..."
                Thread {
                    try {
                        exportDbZip(uri)
                        runOnUiThread { status.text = "DB exported to db.zip. ${dbStateText()}" }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "DB export failed: ${e.message}" }
                    }
                }.start()
            }
            pickDbZipRequest -> {
                status.text = "Importing db.zip..."
                Thread {
                    try {
                        stopService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP))
                        importDbZip(uri)
                        runOnUiThread { status.text = "DB imported. ${dbStateText()}" }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "DB import failed: ${e.message}" }
                    }
                }.start()
            }
        }
    }

    private fun importAssetsFolder(uri: Uri) {
        val picked = DocumentFile.fromTreeUri(this, uri) ?: error("Cannot open selected folder")
        val sourceAssets = if (picked.name == "assets") picked else picked.findFile("assets")
        sourceAssets ?: error("Selected folder must be assets/ or contain assets/")

        val tmpAssets = File(filesDir, "server/assets_import_tmp")
        tmpAssets.deleteRecursively()
        tmpAssets.mkdirs()
        copyDocumentTree(sourceAssets, tmpAssets)
        replaceAssets(tmpAssets)
    }

    private fun importAssetsTar(uri: Uri) {
        val serverDir = File(filesDir, "server").apply { mkdirs() }
        val tarFile = File(serverDir, "assets.tar")

        copyUriToFile(uri, tarFile)
        runOnUiThread { status.text = "Extracting only master data from assets.tar..." }
        extractMasterDataFromTar(tarFile)
        File(serverDir, "ready.state").delete()
    }

    private fun extractMasterDataFromTar(tarFile: File) {
        val wantedWithRoot = "assets/release/20240404193219.bin.e"
        val wantedWithoutRoot = "release/20240404193219.bin.e"
        val outFile = File(filesDir, "server/assets/release/20240404193219.bin.e")
        outFile.parentFile?.mkdirs()

        TarArchiveInputStream(BufferedInputStream(tarFile.inputStream())).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (!entry.isFile) continue
                val cleanName = normalizeTarName(entry.name)
                if (cleanName == wantedWithRoot || cleanName == wantedWithoutRoot) {
                    outFile.outputStream().use { output -> tar.copyTo(output, 1024 * 1024) }
                    return
                }
            }
        }
        error("assets.tar does not contain assets/release/20240404193219.bin.e")
    }

    private fun replaceAssets(tmpAssets: File) {
        val marker = File(tmpAssets, "release/20240404193219.bin.e")
        if (!marker.isFile) {
            error("Imported data does not look like assets/. Missing release/20240404193219.bin.e")
        }

        val targetAssets = File(filesDir, "server/assets")
        targetAssets.deleteRecursively()
        if (!tmpAssets.renameTo(targetAssets)) {
            copyPlainDirectory(tmpAssets, targetAssets)
            tmpAssets.deleteRecursively()
        }
        File(filesDir, "server/tar_import_tmp").deleteRecursively()
        File(filesDir, "server/assets_import_tmp").deleteRecursively()
        File(filesDir, "server/ready.state").delete()
    }

    private fun copyDocumentTree(src: DocumentFile, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles().forEach { child ->
                copyDocumentTree(child, File(dst, child.name ?: "unnamed"))
            }
        } else if (src.isFile) {
            dst.parentFile?.mkdirs()
            contentResolver.openInputStream(src.uri).use { input ->
                requireNotNull(input) { "Cannot read ${src.name}" }
                dst.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun copyUriToFile(uri: Uri, dst: File) {
        dst.parentFile?.mkdirs()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            dst.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
        }
    }

    private fun exportDbZip(uri: Uri) {
        val dbDir = File(filesDir, "server/db")
        if (!dbDir.isDirectory) error("DB folder does not exist")
        contentResolver.openOutputStream(uri).use { rawOutput ->
            requireNotNull(rawOutput) { "Cannot open export target" }
            ZipOutputStream(BufferedOutputStream(rawOutput)).use { zip ->
                zipDirectory(dbDir, dbDir, zip)
            }
        }
    }

    private fun zipDirectory(root: File, current: File, zip: ZipOutputStream) {
        current.listFiles()?.forEach { file ->
            val relative = root.toURI().relativize(file.toURI()).path.replace('\\', '/')
            if (file.isDirectory) {
                if (relative.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry(if (relative.endsWith('/')) relative else "$relative/"))
                    zip.closeEntry()
                }
                zipDirectory(root, file, zip)
            } else if (file.isFile) {
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { input -> input.copyTo(zip, 1024 * 1024) }
                zip.closeEntry()
            }
        }
    }

    private fun importDbZip(uri: Uri) {
        val serverDir = File(filesDir, "server").apply { mkdirs() }
        val tmpDb = File(serverDir, "db_import_tmp")
        val targetDb = File(serverDir, "db")
        tmpDb.deleteRecursively()
        tmpDb.mkdirs()

        contentResolver.openInputStream(uri).use { rawInput ->
            requireNotNull(rawInput) { "Cannot open db.zip" }
            ZipInputStream(BufferedInputStream(rawInput)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val cleanName = normalizeArchiveName(entry.name)
                    if (cleanName.isBlank()) {
                        zip.closeEntry()
                        continue
                    }
                    val outFile = File(tmpDb, cleanName).canonicalFile
                    val canonicalBase = tmpDb.canonicalFile
                    if (!outFile.path.startsWith(canonicalBase.path + File.separator) && outFile != canonicalBase) {
                        error("Blocked unsafe zip path: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> zip.copyTo(output, 1024 * 1024) }
                    }
                    zip.closeEntry()
                }
            }
        }

        val importedFiles = tmpDb.walkTopDown().filter { it.isFile }.toList()
        if (importedFiles.isEmpty()) error("db.zip is empty")
        val hasDb = importedFiles.any { it.extension.equals("db", ignoreCase = true) || it.name.endsWith(".sqlite", ignoreCase = true) }
        if (!hasDb) error("db.zip does not look like a DB backup")

        targetDb.deleteRecursively()
        if (!tmpDb.renameTo(targetDb)) {
            copyPlainDirectory(tmpDb, targetDb)
            tmpDb.deleteRecursively()
        }
        File(serverDir, "ready.state").delete()
    }

    private fun normalizeArchiveName(name: String): String {
        val cleaned = name.replace('\\', '/')
            .removePrefix("./")
            .trimStart('/')
        return cleaned.removePrefix("db/")
    }

    private fun normalizeTarName(name: String): String {
        return name.replace('\\', '/')
            .removePrefix("./")
            .trimStart('/')
    }

    private fun copyPlainDirectory(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child -> copyPlainDirectory(child, File(dst, child.name)) }
        } else if (src.isFile) {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    private fun startServer(localId: Int, lanId: Int, manualId: Int) {
        if (!assetsLookOk()) {
            status.text = "Assets are missing. Import assets first.\n${assetStateText()}"
            return
        }
        val selected = modeGroup.checkedRadioButtonId
        val host = when (selected) {
            localId -> "127.0.0.1"
            lanId -> WifiIp.find(this) ?: "127.0.0.1"
            manualId -> manualIp.text.toString().trim().ifBlank { "127.0.0.1" }
            else -> "127.0.0.1"
        }
        val intent = Intent(this, ServerService::class.java)
            .setAction(ServerService.ACTION_START)
            .putExtra(ServerService.EXTRA_HOST, host)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Starting server for $host...\nCDN may spend time indexing assets.tar. Wait for READY."
        startAutoRefresh()
    }

    private fun stopServer() {
        startService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP))
        status.text = "Stopping server..."
        uiHandler.removeCallbacks(autoRefreshRunnable)
    }

    private fun startAutoRefresh() {
        autoRefreshUntil = System.currentTimeMillis() + 10 * 60 * 1000L
        uiHandler.removeCallbacks(autoRefreshRunnable)
        uiHandler.postDelayed(autoRefreshRunnable, 1500)
    }

    private fun refreshStatus() {
        val log = File(filesDir, "server/launcher.log")
        val header = readinessText()
        status.text = if (log.exists()) {
            val tail = log.readLines().takeLast(100).joinToString("\n")
            if (tail.isBlank()) header + "\n\nLog is empty." else header + "\n\n" + tail
        } else {
            header + "\n\nNo log yet. Assets: ${assetStateText()}"
        }
    }

    private fun clearLogs() {
        try {
            File(filesDir, "server/launcher.log").writeText("")
            status.text = readinessText() + "\n\nLog cleared."
        } catch (e: Exception) {
            status.text = "Clear logs failed: ${e.message}"
        }
    }

    private fun readinessText(): String {
        val readyFile = File(filesDir, "server/ready.state")
        val db = dbStateText()
        return when {
            readyFile.isFile -> "READY. ${assetStateText()}. $db"
            File(filesDir, "server/assets.tar").isFile -> "Starting / indexing assets.tar. Wait for READY. ${assetStateText()}. $db"
            else -> "Not ready. ${assetStateText()}. $db"
        }
    }

    private fun initialStatusText(): String {
        return "Ready. ${assetStateText()}. ${dbStateText()}"
    }

    private fun assetsLookOk(): Boolean {
        val master = File(filesDir, "server/assets/release/20240404193219.bin.e")
        val tar = File(filesDir, "server/assets.tar")
        return master.isFile && (tar.isFile || File(filesDir, "server/assets/revisions").isDirectory)
    }

    private fun assetStateText(): String {
        val master = File(filesDir, "server/assets/release/20240404193219.bin.e")
        val tar = File(filesDir, "server/assets.tar")
        return when {
            tar.isFile && master.isFile -> "Assets: OK, direct tar mode"
            master.isFile -> "Assets: OK, folder mode"
            else -> "Assets: missing"
        }
    }

    private fun dbStateText(): String {
        val dbDir = File(filesDir, "server/db")
        val files = dbDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val size = files.sumOf { it.length() }
        return if (files.isEmpty()) {
            "DB: empty"
        } else {
            "DB: ${files.size} file(s), ${humanSize(size)}"
        }
    }

    private fun humanSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) "${bytes} B" else String.format("%.1f %s", value, units[index])
    }
}
