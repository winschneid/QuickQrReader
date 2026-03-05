package com.ks.app.quickqrreader.domain

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.ks.app.quickqrreader.data.AppRepository
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

sealed class QrCodeProcessingResult {
    data class Success(val intent: Intent) : QrCodeProcessingResult()
    data class Error(val originalQrCode: String) : QrCodeProcessingResult()
}

class HandleQrCodeUseCase(private val appRepository: AppRepository) {

    private val lineAppPackageName = "jp.naver.line.android"
    private val twitterAppPackageName = "com.twitter.android"
    private val xAppPackageName = "com.x.android"
    private val instagramAppPackageName = "com.instagram.android"

    operator fun invoke(qrCode: String): QrCodeProcessingResult {
        return try {
            val intent = when {
                isWifiQrCode(qrCode) -> createWifiIntent()
                isVCardQrCode(qrCode) -> createVCardIntent(qrCode)
                isVCalendarQrCode(qrCode) -> createVCalendarIntent(qrCode)
                else -> createUriIntent(qrCode)
            }
            QrCodeProcessingResult.Success(intent)
        } catch (e: Exception) {
            QrCodeProcessingResult.Error(qrCode)
        }
    }

    private fun createUriIntent(qrCode: String): Intent {
        val uri = Uri.parse(qrCode)
        val scheme = uri?.scheme?.lowercase()
        return when {
            scheme == "line" -> createAppIntent(uri!!, lineAppPackageName)
            scheme == "twitter" -> createAppIntent(uri!!, twitterAppPackageName, checkSecondPackage = xAppPackageName)
            scheme == "instagram" -> createAppIntent(uri!!, instagramAppPackageName)
            scheme == "http" || scheme == "https" -> when {
                qrCode.contains("line.me") -> createAppIntent(uri!!, lineAppPackageName)
                qrCode.contains("twitter.com") || qrCode.contains("x.com") ->
                    createAppIntent(uri!!, twitterAppPackageName, checkSecondPackage = xAppPackageName)
                qrCode.contains("instagram.com") -> createAppIntent(uri!!, instagramAppPackageName)
                else -> Intent(Intent.ACTION_VIEW, uri!!)
            }
            scheme != null -> Intent(Intent.ACTION_VIEW, uri!!)  // mailto:, tel:, sms:, geo:, otpauth:, etc.
            else -> Intent(Intent.ACTION_SEND).apply {            // no scheme = plain text
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, qrCode)
            }
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
        if (appRepository.isAppInstalled(packageName)) return appIntent
        if (checkSecondPackage != null) {
            val secondAppIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(checkSecondPackage)
            }
            if (appRepository.isAppInstalled(checkSecondPackage)) return secondAppIntent
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(null)
        }
    }

    private fun isWifiQrCode(qrCode: String) = qrCode.startsWith("WIFI:", ignoreCase = true)

    private fun createWifiIntent(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS)

    private fun isVCardQrCode(qrCode: String) =
        qrCode.trimStart().startsWith("BEGIN:VCARD", ignoreCase = true)

    private fun createVCardIntent(vCard: String): Intent {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
        }
        val name = extractField(vCard, "FN") ?: run {
            extractField(vCard, "N")?.split(";")?.let { parts ->
                listOfNotNull(
                    parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                    parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                ).joinToString(" ").takeIf { it.isNotBlank() }
            }
        }
        name?.let { intent.putExtra(ContactsContract.Intents.Insert.NAME, it) }
        extractField(vCard, "TEL")?.let { intent.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        extractField(vCard, "EMAIL")?.let { intent.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        extractField(vCard, "ORG")?.let { intent.putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        return intent
    }

    private fun isVCalendarQrCode(qrCode: String): Boolean {
        val trimmed = qrCode.trimStart()
        return trimmed.startsWith("BEGIN:VCALENDAR", ignoreCase = true) ||
                trimmed.startsWith("BEGIN:VEVENT", ignoreCase = true)
    }

    private fun createVCalendarIntent(vCal: String): Intent {
        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            extractField(vCal, "SUMMARY")?.let { putExtra(CalendarContract.Events.TITLE, it) }
            extractField(vCal, "DESCRIPTION")?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            extractField(vCal, "LOCATION")?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            extractField(vCal, "DTSTART")?.let { parseICalDateTime(it) }
                ?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            extractField(vCal, "DTEND")?.let { parseICalDateTime(it) }
                ?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        }
    }

    // Extracts a field value from vCard/vCalendar text.
    // Handles optional parameters (e.g. TEL;TYPE=CELL:+1234 → "+1234")
    private fun extractField(text: String, field: String): String? {
        val pattern = Regex("^$field(?:;[^:]*)?:(.+)$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        return pattern.find(text)?.groupValues?.get(1)?.trim()
    }

    private fun parseICalDateTime(dtString: String): Long? {
        return try {
            val isUtc = dtString.endsWith("Z")
            val clean = dtString.trimEnd('Z')
            val sdf = if (clean.length == 8) {
                SimpleDateFormat("yyyyMMdd", Locale.US)
            } else {
                SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
            }
            if (isUtc) sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(clean)?.time
        } catch (e: Exception) {
            null
        }
    }
}
