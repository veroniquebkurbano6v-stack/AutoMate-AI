package com.palmagent.app.utils

import android.graphics.Bitmap

/**
 * Bitmap 生命周期管理扩展，确保使用后自动回收
 */
suspend fun <T> Bitmap.useSafely(block: suspend (Bitmap) -> T): T {
    return try {
        block(this)
    } finally {
        if (!isRecycled) {
            try { recycle() } catch (_: Exception) {}
        }
    }
}

/**
 * 安全回收 Bitmap，避免重复回收异常
 */
fun Bitmap?.recycleSafely() {
    if (this != null && !isRecycled) {
        try { recycle() } catch (_: Exception) {}
    }
}
