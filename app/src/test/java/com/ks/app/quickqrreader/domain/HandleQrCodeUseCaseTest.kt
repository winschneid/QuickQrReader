package com.ks.app.quickqrreader.domain

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
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
        assertNull(intent.`package`)
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
        assertNull(intent.`package`)
    }

    @Test
    fun `invoke with generic HTTP URL should return Success with generic intent`() {
        val qrCode = "http://www.google.com"
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

    @Test
    fun `invoke with plain text QR code should return Success with share intent`() {
        val result = handleQrCodeUseCase("Hello World")

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_SEND, intent.action)
    }

    @Test
    fun `invoke with vCard QR code should return Success with insert contact intent`() {
        val qrCode = """
            BEGIN:VCARD
            VERSION:3.0
            FN:John Doe
            TEL;TYPE=CELL:+1234567890
            EMAIL:john@example.com
            ORG:ACME Corp
            END:VCARD
        """.trimIndent()

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(ContactsContract.Intents.Insert.ACTION, intent.action)
    }

    @Test
    fun `invoke with vCalendar QR code should return Success with insert calendar intent`() {
        val qrCode = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:Team Meeting
            DTSTART:20260310T100000Z
            DTEND:20260310T110000Z
            LOCATION:Conference Room
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_INSERT, intent.action)
    }

    @Test
    fun `invoke with bare VEVENT QR code should return Success with insert calendar intent`() {
        val qrCode = """
            BEGIN:VEVENT
            SUMMARY:Quick Event
            DTSTART:20260315T090000Z
            DTEND:20260315T100000Z
            END:VEVENT
        """.trimIndent()

        val result = handleQrCodeUseCase(qrCode)

        assertTrue(result is QrCodeProcessingResult.Success)
        val intent = (result as QrCodeProcessingResult.Success).intent
        assertEquals(Intent.ACTION_INSERT, intent.action)
    }
}
