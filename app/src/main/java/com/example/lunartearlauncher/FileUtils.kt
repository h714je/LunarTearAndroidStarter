package com.example.lunartearlauncher

import java.io.File

fun copyPlainDirectory(src: File, dst: File) {
    if (src.isDirectory) {
        dst.mkdirs()
        src.listFiles()?.forEach { child -> copyPlainDirectory(child, File(dst, child.name)) }
    } else if (src.isFile) {
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
    }
}

fun normalizeTarName(name: String): String {
    return name.replace('\\', '/')
        .removePrefix("./")
        .trimStart('/')
}
