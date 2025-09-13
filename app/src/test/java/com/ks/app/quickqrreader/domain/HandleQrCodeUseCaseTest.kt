package com.ks.app.quickqrreader.domain

import android.content.Intent
import android.net.Uri
import com.ks.app.quickqrreader.data.AppRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

class HandleQrCodeUseCaseTest {

    @Mock
    private lateinit var mockAppRepository: AppRepository

    private lateinit var handleQrCodeUseCase: HandleQrCodeUseCase

    // Package names used by the UseCase (copied for test clarity)
    private val lineAppPackageName = "jp.naver.line.android"
    private val twitterAppPackageName = "com.twitter.android"
    private val xAppPackageName = "com.x.android"
    private val instagramAppPackageName = "com.instagram.android"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        handleQrCodeUseCase = HandleQrCodeUseCase(mockAppRepository)
    }

    private fun mockAppInstallationStatus(packageName: String, isInstalled: Boolean) {
        `when`(mockAppRepository.isAppInstalled(packageName)).thenReturn(isInstalled)
    }

    @Test
    fun `invoke with LINE scheme when LINE app installed should return Success with LINE intent`() {
        val qrCode = "line://ti/p/@example"
        mockAppInstallationStatus(lineAppPackageName, true)

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(qrCode), intent.data)
        assertEquals(lineAppPackageName, intent.`package`)
    }

    @Test
    fun `invoke with LINE URL when LINE app NOT installed should return Success with browser intent`() {
        val qrCode = "https://line.me/R/ti/p/@example"
        mockAppInstallationStatus(lineAppPackageName, false)

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(qrCode), intent.data)
        assertNull(intent.`package`) // Fallback to browser
    }

    @Test
    fun `invoke with Twitter URL when only X app installed should return Success with X intent`() {
        val qrCode = "https://twitter.com/someuser"
        mockAppInstallationStatus(twitterAppPackageName, false)
        mockAppInstallationStatus(xAppPackageName, true)

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(qrCode), intent.data)
        assertEquals(xAppPackageName, intent.`package`)
    }

    @Test
    fun `invoke with Instagram scheme when Instagram app NOT installed should return Success with browser-like intent`() {
        val qrCode = "instagram://user?username=someuser"
        mockAppInstallationStatus(instagramAppPackageName, false)

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(qrCode), intent.data)
        assertNull(intent.`package`) // Fallback, no specific package for scheme if app not found
    }

    @Test
    fun `invoke with generic HTTP URL should return Success with generic intent`() {
        val qrCode = "http://www.google.com"
        // Assume all specific apps are not installed for this generic test
        mockAppInstallationStatus(lineAppPackageName, false)
        mockAppInstallationStatus(twitterAppPackageName, false)
        mockAppInstallationStatus(xAppPackageName, false)
        mockAppInstallationStatus(instagramAppPackageName, false)

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(qrCode), intent.data)
        assertNull(intent.`package`)
    }

    @Test
    fun `invoke with invalid URI string should return Error`() {
        val invalidQrCode = "invalid_uri_string_without_scheme_or_domain"
        // No need to mock app installation status as Uri.parse should fail first

        val result = handleQrCodeUseCase(invalidQrCode)

        assertTrue(result is QrCodeProcessingResult.Error)
        assertEquals(invalidQrCode, (result as QrCodeProcessingResult.Error).originalQrCode)
    }
    
    @Test
    fun `invoke with LINE URL when LINE app installed should return Success with LINE intent`() {
        val qrCode = "https://line.me/R/ti/p/@example"
        mockAppInstallationStatus(lineAppPackageName, true)

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(qrCode), intent.data)
        assertEquals(lineAppPackageName, intent.`package`)
    }

    @Test
    fun `invoke with Instagram URL when Instagram app installed should return Success with Instagram intent`() {
        val qrCode = "https://www.instagram.com/someprofile/"
        mockAppInstallationStatus(instagramAppPackageName, true)

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(qrCode), intent.data)
        assertEquals(instagramAppPackageName, intent.`package`)
    }
}
