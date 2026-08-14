package com.palmagent.app.data.repository

import android.graphics.Bitmap
import com.palmagent.app.domain.repository.ScreenRepository
import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.service.RapidOcrService
import com.palmagent.app.service.ScreenAnalyzer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenRepositoryImpl @Inject constructor(
    private val screenAnalyzer: ScreenAnalyzer
) : ScreenRepository {

    override suspend fun getScreenInfo(): ScreenInfo? {
        return GUIAccessibilityService.instance?.getCurrentScreenInfo()
    }

    override suspend fun captureScreen(): Bitmap? {
        return screenAnalyzer.takeScreenshot()
    }

    override suspend fun extractText(bitmap: Bitmap): List<String> {
        return RapidOcrService.extractPlainTextList(bitmap)
    }

    override fun isAccessibilityReady(): Boolean {
        return GUIAccessibilityService.instance != null
    }
}
