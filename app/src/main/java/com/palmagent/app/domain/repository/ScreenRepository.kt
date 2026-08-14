package com.palmagent.app.domain.repository

import android.graphics.Bitmap
import com.palmagent.app.model.ScreenInfo

interface ScreenRepository {
    suspend fun getScreenInfo(): ScreenInfo?
    suspend fun captureScreen(): Bitmap?
    suspend fun extractText(bitmap: Bitmap): List<String>
    fun isAccessibilityReady(): Boolean
}
