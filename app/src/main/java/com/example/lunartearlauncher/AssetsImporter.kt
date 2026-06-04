package com.example.lunartearlauncher

import android.app.Activity
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class AssetsImporter(
    private val activity: Activity,
    private val listener: Listener
) {
    interface Listener {
        fun onAssetImportMessage(text: String)
    }

    @Volatile private var assetsImportRunning = false
    @Volatile private var assetsImportPhase = ""
    @Volatile private var assetsImportBytesCopied = 0L
    @Volatile private var assetsImportBytesTotal = -1L
    @Volatile private var assetsImportFilesCopied = 0
    @Volatile private var assetsImportCurrentName = ""
    @Volatile private var lastImportUiUpdate = 0L

    fun isRunning(): Boolean = assetsImportRunning

    fun begin(phase: String, totalBytes: Long) {
        assetsImportRunning = true
        assetsImportPhase = phase
        assetsImportBytesCopied = 0L
        assetsImportBytesTotal = totalBytes
        assetsImportFilesCopied = 0
        assetsImportCurrentName = ""
        lastImportUiUpdate = 0L
        updateAssetImportProgress(phase, 0L, totalBytes, 0, "", force = true)
    }

    fun finish(message: String) {
        assetsImportRunning = false
        assetsImportPhase = "Done"
        assetsImportCurrentName = ""
        listener.onAssetImportMessage(message)
    }

    fun fail(message: String?) {
        assetsImportRunning = false
        assetsImportPhase = "Failed"
        assetsImportCurrentName = ""
        listener.onAssetImportMessage(message ?: "Assets import failed")
    }

    fun importAssetsFolder(uri: Uri) {
        val picked = DocumentFile.fromTreeUri(activity, uri) ?: error("Cannot open selected folder")
        val sourceAssets = if (picked.name == "assets") picked else picked.findFile("assets")
        sourceAssets ?: error("Selected folder must be assets/ or contain assets/")

        updateAssetImportProgress("Scanning assets folder", 0L, -1L, 0, "")
        val total = documentTreeSize(sourceAssets)
        ensureEnoughSpace(total.bytes)
        begin("Copying assets folder", total.bytes)

        val tmpAssets = File(activity.filesDir, "server/assets_import_tmp")
        tmpAssets.deleteRecursively()
        tmpAssets.mkdirs()
        copyDocumentTree(sourceAssets, tmpAssets, total.bytes)

        updateAssetImportProgress("Validating imported assets", assetsImportBytesCopied, total.bytes, assetsImportFilesCopied, "")
        replaceAssets(tmpAssets)
    }

    fun importAssetsTar(uri: Uri) {
        val serverDir = File(activity.filesDir, "server").apply { mkdirs() }
        val tarFile = File(serverDir, "assets.tar")
        val total = documentSize(uri)
        ensureEnoughSpace(total)

        updateAssetImportProgress("Copying assets.tar", 0L, total, 0, "assets.tar")
        copyUriToFile(uri, tarFile, "Copying assets.tar", total)

        updateAssetImportProgress("Finding master data in assets.tar", 0L, tarFile.length(), 0, "")
        val outFile = File(activity.filesDir, "server/assets/release/20240404193219.bin.e")
        TarMasterExtractor.extract(tarFile, outFile) { phase, copiedBytes, totalBytes, filesSeen, currentName, force ->
            updateAssetImportProgress(phase, copiedBytes, totalBytes, filesSeen, currentName, force)
        }
        File(serverDir, "ready.state").delete()
    }

    fun documentSize(uri: Uri): Long {
        return try {
            DocumentFile.fromSingleUri(activity, uri)?.length()?.takeIf { it > 0 } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    fun statusText(): String {
        if (!assetsImportRunning && assetsImportPhase.isBlank()) return "Idle"
        if (!assetsImportRunning && assetsImportPhase == "Done") return "Done"
        if (!assetsImportRunning && assetsImportPhase == "Failed") return "Failed"

        val total = assetsImportBytesTotal
        val copied = assetsImportBytesCopied
        val percent = if (total > 0L) {
            " ${((copied * 100.0) / total).coerceIn(0.0, 100.0).let { String.format("%.1f%%", it) }}"
        } else {
            ""
        }
        val sizeText = if (total > 0L) {
            "${humanSize(copied)} / ${humanSize(total)}"
        } else if (copied > 0L) {
            humanSize(copied)
        } else {
            "size unknown"
        }
        val fileText = if (assetsImportFilesCopied > 0) ", ${assetsImportFilesCopied} file(s)" else ""
        val current = assetsImportCurrentName.takeIf { it.isNotBlank() }?.let { "\nNow: $it" } ?: ""
        return "$assetsImportPhase$percent\n$sizeText$fileText$current"
    }

    private fun replaceAssets(tmpAssets: File) {
        val marker = File(tmpAssets, "release/20240404193219.bin.e")
        if (!marker.isFile) {
            error("Imported data does not look like assets/. Missing release/20240404193219.bin.e")
        }

        val targetAssets = File(activity.filesDir, "server/assets")
        targetAssets.deleteRecursively()
        if (!tmpAssets.renameTo(targetAssets)) {
            copyPlainDirectory(tmpAssets, targetAssets)
            tmpAssets.deleteRecursively()
        }
        File(activity.filesDir, "server/tar_import_tmp").deleteRecursively()
        File(activity.filesDir, "server/assets_import_tmp").deleteRecursively()
        File(activity.filesDir, "server/ready.state").delete()
    }

    private fun copyDocumentTree(src: DocumentFile, dst: File, totalBytes: Long) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles().forEach { child ->
                copyDocumentTree(child, File(dst, child.name ?: "unnamed"), totalBytes)
            }
        } else if (src.isFile) {
            dst.parentFile?.mkdirs()
            assetsImportCurrentName = src.name ?: "file"
            activity.contentResolver.openInputStream(src.uri).use { input ->
                requireNotNull(input) { "Cannot read ${src.name}" }
                BufferedOutputStream(dst.outputStream()).use { output ->
                    copyWithAssetProgress(input, output, totalBytes, "Copying assets folder")
                }
            }
            assetsImportFilesCopied += 1
        }
    }

    private fun copyUriToFile(uri: Uri, dst: File, phase: String, totalBytes: Long) {
        dst.parentFile?.mkdirs()
        activity.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            BufferedOutputStream(dst.outputStream()).use { output ->
                copyWithAssetProgress(input, output, totalBytes, phase)
            }
        }
    }

    private fun copyWithAssetProgress(input: InputStream, output: OutputStream, totalBytes: Long, phase: String) {
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            assetsImportBytesCopied += read.toLong()
            updateAssetImportProgress(
                phase,
                assetsImportBytesCopied,
                totalBytes,
                assetsImportFilesCopied,
                assetsImportCurrentName
            )
        }
    }

    private data class DocumentTreeStats(val bytes: Long, val files: Int)

    private fun documentTreeSize(root: DocumentFile): DocumentTreeStats {
        var bytes = 0L
        var files = 0
        fun walk(node: DocumentFile) {
            if (node.isDirectory) {
                node.listFiles().forEach { walk(it) }
            } else if (node.isFile) {
                val length = node.length()
                if (length > 0) bytes += length
                files += 1
                if (files % 250 == 0) {
                    updateAssetImportProgress("Scanning assets folder", bytes, -1L, files, node.name ?: "file")
                }
            }
        }
        walk(root)
        updateAssetImportProgress("Folder scan complete", bytes, bytes, files, "")
        assetsImportFilesCopied = 0
        assetsImportBytesCopied = 0L
        return DocumentTreeStats(bytes, files)
    }

    private fun ensureEnoughSpace(requiredBytes: Long) {
        if (requiredBytes <= 0L) return
        val safetyMargin = 512L * 1024L * 1024L
        val free = activity.filesDir.usableSpace
        if (free < requiredBytes + safetyMargin) {
            error("Not enough internal storage. Need about ${humanSize(requiredBytes + safetyMargin)}, available ${humanSize(free)}")
        }
    }

    private fun updateAssetImportProgress(
        phase: String,
        copiedBytes: Long,
        totalBytes: Long,
        filesCopied: Int,
        currentName: String,
        force: Boolean = false
    ) {
        assetsImportPhase = phase
        assetsImportBytesCopied = copiedBytes
        assetsImportBytesTotal = totalBytes
        assetsImportFilesCopied = filesCopied
        assetsImportCurrentName = currentName

        val now = System.currentTimeMillis()
        if (!force && now - lastImportUiUpdate < 700L) return
        lastImportUiUpdate = now
        listener.onAssetImportMessage(statusText())
    }
}
