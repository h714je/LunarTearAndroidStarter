package com.example.lunartearlauncher

import android.app.Activity
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DbBackup(private val activity: Activity) {
    fun exportDbZip(uri: Uri) {
        val dbDir = File(activity.filesDir, "server/db")
        if (!dbDir.isDirectory) error("DB folder does not exist")
        activity.contentResolver.openOutputStream(uri).use { rawOutput ->
            requireNotNull(rawOutput) { "Cannot open export target" }
            ZipOutputStream(BufferedOutputStream(rawOutput)).use { zip ->
                zipDirectory(dbDir, dbDir, zip)
            }
        }
    }

    fun importDbZip(uri: Uri) {
        val serverDir = File(activity.filesDir, "server").apply { mkdirs() }
        val tmpDb = File(serverDir, "db_import_tmp")
        val targetDb = File(serverDir, "db")
        tmpDb.deleteRecursively()
        tmpDb.mkdirs()

        activity.contentResolver.openInputStream(uri).use { rawInput ->
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

    private fun normalizeArchiveName(name: String): String {
        val cleaned = name.replace('\\', '/')
            .removePrefix("./")
            .trimStart('/')
        return cleaned.removePrefix("db/")
    }
}
