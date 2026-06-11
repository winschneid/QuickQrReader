package com.ks.app.quickqrreader.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// SharedPreferences と JSONArray の実挙動が必要なため Robolectric 上で実行する
@RunWith(RobolectricTestRunner::class)
class ScanHistoryRepositoryTest {

    private lateinit var repository: SharedPrefsScanHistoryRepository

    @Before
    fun setUp() {
        repository = SharedPrefsScanHistoryRepository(
            ApplicationProvider.getApplicationContext(),
            maxEntries = 3
        )
    }

    @Test
    fun `getHistory returns empty list initially`() {
        assertEquals(emptyList<String>(), repository.getHistory())
    }

    @Test
    fun `addEntry prepends values and persists them`() {
        repository.addEntry("first")
        repository.addEntry("second")

        assertEquals(listOf("second", "first"), repository.getHistory())
        // 新しいインスタンスでも読めること（永続化の確認）
        val reloaded = SharedPrefsScanHistoryRepository(ApplicationProvider.getApplicationContext())
        assertEquals(listOf("second", "first"), reloaded.getHistory())
    }

    @Test
    fun `addEntry moves duplicate to front instead of duplicating`() {
        repository.addEntry("a")
        repository.addEntry("b")
        repository.addEntry("a")

        assertEquals(listOf("a", "b"), repository.getHistory())
    }

    @Test
    fun `addEntry drops oldest entries beyond max size`() {
        repository.addEntry("1")
        repository.addEntry("2")
        repository.addEntry("3")
        repository.addEntry("4")

        assertEquals(listOf("4", "3", "2"), repository.getHistory())
    }

    @Test
    fun `values containing newlines and delimiters survive a round trip`() {
        val vCard = "BEGIN:VCARD\nFN:John \"Doe\";Jr,\nEND:VCARD"
        repository.addEntry(vCard)

        assertEquals(listOf(vCard), repository.getHistory())
    }
}
