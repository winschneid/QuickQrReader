package com.ks.app.quickqrreader.data

import android.content.Context
import android.content.pm.PackageManager

interface AppRepository {
    fun isAppInstalled(packageName: String): Boolean
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
}
