package com.ks.app.quickqrreader.domain

import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.ks.app.quickqrreader.data.AppRepository
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

sealed class QrCodeProcessingResult {
    data class Success(val intent: Intent) : QrCodeProcessingResult()
    data class Error(val originalQrCode: String) : QrCodeProcessingResult()
}

sealed class DomainRule {
    data class OpenInApp(
        val packageName: String,
        val fallbackPackage: String? = null
    ) : DomainRule()
    object OpenInBrowser : DomainRule()
}

data class DomainRoutingRule(
    val matchHost: (String) -> Boolean,
    val rule: DomainRule
)

class HandleQrCodeUseCase(private val appRepository: AppRepository) {

    private val lineAppPackageName = "jp.naver.line.android"
    private val twitterAppPackageName = "com.twitter.android"
    private val xAppPackageName = "com.x.android"
    private val instagramAppPackageName = "com.instagram.android"

    private val domainRules = listOf(
        DomainRoutingRule(
            matchHost = { it == "line.me" || it.endsWith(".line.me") },
            rule = DomainRule.OpenInApp(lineAppPackageName)
        ),
        DomainRoutingRule(
            matchHost = { it == "twitter.com" || it.endsWith(".twitter.com") || it == "x.com" || it.endsWith(".x.com") },
            rule = DomainRule.OpenInApp(twitterAppPackageName, fallbackPackage = xAppPackageName)
        ),
        DomainRoutingRule(
            matchHost = { it == "instagram.com" || it.endsWith(".instagram.com") },
            rule = DomainRule.OpenInApp(instagramAppPackageName)
        ),
        DomainRoutingRule(
            matchHost = { it == "eplus.jp" || it.endsWith(".eplus.jp") },
            rule = DomainRule.OpenInBrowser
        ),
    )

    operator fun invoke(qrCode: String): QrCodeProcessingResult {
        return try {
            val intent = when {
                isWifiQrCode(qrCode) -> createWifiIntent(qrCode)
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
            scheme == "twitter" -> createAppIntent(uri!!, twitterAppPackageName, fallbackPackage = xAppPackageName)
            scheme == "instagram" -> createAppIntent(uri!!, instagramAppPackageName)
            scheme == "http" || scheme == "https" -> {
                val host = uri!!.host?.lowercase() ?: ""
                when (val rule = domainRules.find { it.matchHost(host) }?.rule) {
                    is DomainRule.OpenInApp -> createAppIntent(uri, rule.packageName, rule.fallbackPackage)
                    DomainRule.OpenInBrowser -> createBrowserIntent(uri)
                    null -> Intent(Intent.ACTION_VIEW, uri)
                }
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
        fallbackPackage: String? = null
    ): Intent {
        if (appRepository.isAppInstalled(packageName)) {
            return Intent(Intent.ACTION_VIEW, uri).apply { setPackage(packageName) }
        }
        if (fallbackPackage != null && appRepository.isAppInstalled(fallbackPackage)) {
            return Intent(Intent.ACTION_VIEW, uri).apply { setPackage(fallbackPackage) }
        }
        return Intent(Intent.ACTION_VIEW, uri)
    }

    // App Links による自動ルーティングを回避してブラウザで開く
    private fun createBrowserIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_VIEW, uri).apply {
            appRepository.getDefaultBrowserPackage()?.let { setPackage(it) }
        }
    }

    private fun isWifiQrCode(qrCode: String) = qrCode.startsWith("WIFI:", ignoreCase = true)

    private fun createWifiIntent(qrCode: String): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            createWifiNetworkIntent(qrCode)?.let { return it }
        }
        return Intent(Settings.ACTION_WIFI_SETTINGS)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createWifiNetworkIntent(qrCode: String): Intent? {
        val ssid = extractWifiField(qrCode, "S") ?: return null
        val password = extractWifiField(qrCode, "P")
        val type = extractWifiField(qrCode, "T")?.uppercase()

        // WEP is deprecated and uses different key formats; fall back to WiFi settings
        if (type == "WEP") return null

        val suggestion = try {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (!password.isNullOrEmpty() && type != "NOPASS") {
                if (type == "WPA3" || type == "SAE") {
                    builder.setWpa3Passphrase(password)
                } else {
                    builder.setWpa2Passphrase(password)
                }
            }
            builder.build()
        } catch (e: IllegalArgumentException) {
            // e.g. password too short for WPA2 spec; fall back to WiFi settings
            return null
        }

        val intent = Intent("android.net.wifi.action.WIFI_ADD_NETWORKS").apply {
            putParcelableArrayListExtra("android.net.wifi.extra.WIFI_NETWORK_LIST", arrayListOf(suggestion))
        }
        return intent.takeIf { appRepository.canHandleIntent(it) }
    }

    // Extracts a field from WiFi QR format: WIFI:S:<SSID>;T:<WPA|WEP|nopass>;P:<password>;;
    private fun extractWifiField(qrCode: String, field: String): String? {
        return Regex("(?:^|;)$field:((?:[^;\\\\]|\\\\.)*)")
            .find(qrCode)
            ?.groupValues?.get(1)
            ?.replace("\\\\", "\\")
            ?.replace("\\;", ";")
            ?.replace("\\,", ",")
            ?.replace("\\\"", "\"")
            ?.takeIf { it.isNotEmpty() }
    }

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
