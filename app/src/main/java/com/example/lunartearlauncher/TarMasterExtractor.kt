package com.example.lunartearlauncher

import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile

object TarMasterExtractor {
    private const val MASTER_WITH_ROOT = "assets/release/20240404193219.bin.e"
    private const val MASTER_WITHOUT_ROOT = "release/20240404193219.bin.e"

    fun extract(
        tarFile: File,
        outFile: File,
        onProgress: (
            phase: String,
            copiedBytes: Long,
            totalBytes: Long,
            filesSeen: Int,
            currentName: String,
            force: Boolean
        ) -> Unit
    ) {
        val tmpFile = File(outFile.parentFile, "${outFile.name}.tmp")
        outFile.parentFile?.mkdirs()
        tmpFile.delete()

        RandomAccessFile(tarFile, "r").use { tar ->
            val header = ByteArray(512)
            val buffer = ByteArray(1024 * 1024)
            var filesSeen = 0

            while (tar.filePointer + 512L <= tar.length()) {
                val entryOffset = tar.filePointer
                tar.readFully(header)
                if (isTarZeroBlock(header)) break

                val cleanName = normalizeTarName(tarHeaderPath(header))
                val entrySize = tarHeaderSize(header)
                val type = header[156].toInt().toChar()
                val dataOffset = tar.filePointer
                filesSeen += 1

                onProgress(
                    "Finding master data in assets.tar",
                    entryOffset,
                    tarFile.length(),
                    filesSeen,
                    cleanName,
                    false
                )

                val isRegularFile = type == '0' || type.code == 0
                if (isRegularFile && (cleanName == MASTER_WITH_ROOT || cleanName == MASTER_WITHOUT_ROOT)) {
                    extractEntry(tar, tmpFile, buffer, dataOffset, entrySize, tarFile.length(), filesSeen, cleanName, onProgress)
                    if (!tmpFile.renameTo(outFile)) {
                        tmpFile.copyTo(outFile, overwrite = true)
                        tmpFile.delete()
                    }
                    return
                }

                tar.seek(dataOffset + tarBlockAlignedSize(entrySize))
            }
        }

        error("assets.tar does not contain $MASTER_WITH_ROOT")
    }

    private fun extractEntry(
        tar: RandomAccessFile,
        tmpFile: File,
        buffer: ByteArray,
        dataOffset: Long,
        entrySize: Long,
        tarSize: Long,
        filesSeen: Int,
        cleanName: String,
        onProgress: (
            phase: String,
            copiedBytes: Long,
            totalBytes: Long,
            filesSeen: Int,
            currentName: String,
            force: Boolean
        ) -> Unit
    ) {
        onProgress("Extracting master data", dataOffset, tarSize, filesSeen, cleanName, true)

        var remaining = entrySize
        BufferedOutputStream(tmpFile.outputStream()).use { output ->
            while (remaining > 0L) {
                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) error("Unexpected end of assets.tar while extracting $cleanName")
                output.write(buffer, 0, count)
                remaining -= count.toLong()
                onProgress(
                    "Extracting master data",
                    dataOffset + entrySize - remaining,
                    tarSize,
                    filesSeen,
                    cleanName,
                    false
                )
            }
        }
    }

    private fun tarHeaderPath(header: ByteArray): String {
        val name = tarHeaderString(header, 0, 100)
        val prefix = tarHeaderString(header, 345, 155)
        return if (prefix.isBlank()) name else "$prefix/$name"
    }

    private fun tarHeaderString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = offset + length
        while (end < max && header[end].toInt() != 0) end += 1
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun tarHeaderSize(header: ByteArray): Long {
        val raw = tarHeaderString(header, 124, 12).trim()
        if (raw.isEmpty()) return 0L
        return raw.toLongOrNull(8) ?: 0L
    }

    private fun tarBlockAlignedSize(size: Long): Long {
        return ((size + 511L) / 512L) * 512L
    }

    private fun isTarZeroBlock(header: ByteArray): Boolean {
        return header.all { it.toInt() == 0 }
    }
}
