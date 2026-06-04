package com.example.lunartearlauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun color(hex: String): Int = Color.parseColor(hex)

fun rounded(color: Int, radius: Int): GradientDrawable {
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }
}

fun humanSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "${bytes} B" else String.format("%.1f %s", value, units[index])
}
