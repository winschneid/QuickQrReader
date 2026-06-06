package com.ks.app.quickqrreader.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

interface AppRepository {
    fun isAppInstalled(packageName: String): Boolean
    fun getDefaultBrowserPackage(): String?
    fun canHandleIntent(intent: Intent): Boolean
}

class DefaultAppRepository(private val context: Context) : AppRepository {
    override fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun canHandleIntent(intent: Intent): Boolean {
        return context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    override fun getDefaultBrowserPackage(): String? {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return context.packageManager
            .resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }
}
