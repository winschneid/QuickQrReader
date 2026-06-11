package com.ks.app.quickqrreader.data

import android.content.Context
import org.json.JSONArray

interface ScanHistoryRepository {
    fun getHistory(): List<String>

    /** 履歴の先頭に追加し、更新後のリストを返す。既存の同一値は先頭に移動する。 */
    fun addEntry(value: String): List<String>
}

class SharedPrefsScanHistoryRepository(
    context: Context,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) : ScanHistoryRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getHistory(): List<String> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun addEntry(value: String): List<String> {
        val updated = (listOf(value) + getHistory().filterNot { it == value }).take(maxEntries)
        // QR の値は改行や任意の区切り文字を含み得るため JSON 配列で永続化する
        val array = JSONArray()
        updated.forEach { array.put(it) }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
        return updated
    }

    private companion object {
        const val PREFS_NAME = "scan_history"
        const val KEY_ENTRIES = "entries"
        const val DEFAULT_MAX_ENTRIES = 10
    }
}
