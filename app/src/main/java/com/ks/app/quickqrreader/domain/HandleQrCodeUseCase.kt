package com.ks.app.quickqrreader.domain

import android.content.Intent
import android.net.Uri
import com.ks.app.quickqrreader.data.AppRepository

sealed class QrCodeProcessingResult {
    data class Success(val intent: Intent) : QrCodeProcessingResult()
    data class Error(val originalQrCode: String) : QrCodeProcessingResult() // Can be more specific later
}

class HandleQrCodeUseCase(private val appRepository: AppRepository) {

    // Package name constants, ideally these could come from a configuration or be injected
    private val lineAppPackageName = "jp.naver.line.android"
    private val twitterAppPackageName = "com.twitter.android"
    private val xAppPackageName = "com.x.android"
    private val instagramAppPackageName = "com.instagram.android"

    operator fun invoke(qrCode: String): QrCodeProcessingResult {
        return try {
            val uri = Uri.parse(qrCode)
            val intent = when (uri.scheme) {
                "line" -> createAppIntent(uri, lineAppPackageName)
                "twitter" -> createAppIntent(uri, twitterAppPackageName, checkSecondPackage = xAppPackageName)
                "instagram" -> createAppIntent(uri, instagramAppPackageName)
                else -> {
                    // Fallback to domain-based checking for http/https URIs
                    when {
                        qrCode.contains("line.me") -> createAppIntent(uri, lineAppPackageName)
                        qrCode.contains("twitter.com") || qrCode.contains("x.com") -> {
                            createAppIntent(uri, twitterAppPackageName, checkSecondPackage = xAppPackageName)
                        }
                        qrCode.contains("instagram.com") -> createAppIntent(uri, instagramAppPackageName)
                        else -> Intent(Intent.ACTION_VIEW, uri) // Generic intent for other web URLs or unhandled schemes
                    }
                }
            }
            QrCodeProcessingResult.Success(intent)
        } catch (e: Exception) {
            // Log exception e here if necessary
            QrCodeProcessingResult.Error(qrCode)
        }
    }

    private fun createAppIntent(
        uri: Uri,
        packageName: String,
        checkSecondPackage: String? = null
    ): Intent {
        val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
        }
        if (appRepository.isAppInstalled(packageName)) {
            return appIntent
        }
        if (checkSecondPackage != null) {
            val secondAppIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(checkSecondPackage)
            }
            if (appRepository.isAppInstalled(checkSecondPackage)) {
                return secondAppIntent
            }
        }
        // Fallback to browser if the specified app(s) are not installed or if it's not an app-specific scheme
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(null) // Ensure no package is set for general VIEW intent
        }
    }
}
