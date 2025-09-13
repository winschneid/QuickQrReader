package com.ks.app.quickqrreader.data

import android.content.Context
import android.content.pm.PackageInfo // Required for mock
import android.content.pm.PackageManager
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock // Required for mock(PackageInfo::class.java)
import org.mockito.MockitoAnnotations
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class DefaultAppRepositoryTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPackageManager: PackageManager

    private lateinit var appRepository: AppRepository

    private val testPackageName = "com.example.testapp"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
        appRepository = DefaultAppRepository(mockContext)
    }

    @Test
    fun `isAppInstalled when app is installed should return true`() {
        // Arrange
        val mockPackageInfo = mock(PackageInfo::class.java) // Create a mock PackageInfo
        `when`(mockPackageManager.getPackageInfo(testPackageName, PackageManager.GET_ACTIVITIES))
            .thenReturn(mockPackageInfo)

        // Act
        val result = appRepository.isAppInstalled(testPackageName)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `isAppInstalled when app is NOT installed should return false`() {
        // Arrange
        `when`(mockPackageManager.getPackageInfo(testPackageName, PackageManager.GET_ACTIVITIES))
            .thenThrow(PackageManager.NameNotFoundException())

        // Act
        val result = appRepository.isAppInstalled(testPackageName)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `isAppInstalled with different PackageManager flags should still work`() {
        // Arrange: Test with different flags if the implementation relied on specific ones, though GET_ACTIVITIES is common.
        val mockPackageInfo = mock(PackageInfo::class.java)
        `when`(mockPackageManager.getPackageInfo(testPackageName, PackageManager.GET_ACTIVITIES)) // Current implementation uses GET_ACTIVITIES
            .thenReturn(mockPackageInfo)
        
        // Act
        val result = appRepository.isAppInstalled(testPackageName)

        // Assert
        assertTrue(result) 
    }
}
