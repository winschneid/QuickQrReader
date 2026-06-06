package com.ks.app.quickqrreader.domain

import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Patterns
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
        // QR コードには末尾の改行や前後の空白が含まれることがあるため正規化する
        val normalized = qrCode.trim()
        return try {
            val intent = when {
                isWifiQrCode(normalized) -> createWifiIntent(normalized)
                isVCardQrCode(normalized) -> createVCardIntent(normalized)
                isVCalendarQrCode(normalized) -> createVCalendarIntent(normalized)
                else -> createUriIntent(normalized)
            }
            QrCodeProcessingResult.Success(intent)
        } catch (e: Exception) {
            QrCodeProcessingResult.Error(qrCode)
        }
    }

    private fun createUriIntent(qrCode: String): Intent {
        val uri = Uri.parse(qrCode)
        return when (val scheme = uri?.scheme?.lowercase()) {
            "line" -> createAppIntent(uri!!, lineAppPackageName)
            "twitter" -> createAppIntent(uri!!, twitterAppPackageName, fallbackPackage = xAppPackageName)
            "instagram" -> createAppIntent(uri!!, instagramAppPackageName)
            "http", "https" -> createWebIntent(uri!!)
            "intent" -> createIntentSchemeIntent(qrCode) ?: createTextShareIntent(qrCode)
            null -> {
                // スキームが無い場合でも "www.example.com" のような裸の URL は
                // ブラウザで開けるよう https:// を補完する。URL でなければテキストとして共有。
                if (looksLikeWebUrl(qrCode)) {
                    createWebIntent(Uri.parse("https://$qrCode"))
                } else {
                    createTextShareIntent(qrCode)
                }
            }
            else -> when {
                // "example.com:8080/path" のような scheme 無し + ポート付きの裸URLは、
                // ホスト名が scheme として誤認される（"://" を含まず scheme にドットを含む）。
                // ドメイン形式なら https の Web URL として扱う。
                !qrCode.contains("://") && scheme.contains('.') && looksLikeWebUrl(qrCode) ->
                    createWebIntent(Uri.parse("https://$qrCode"))

                // mailto:, tel:, sms:, geo:, market:, otpauth: など。
                // 処理できるアプリがある場合のみ起動し、無ければ（"メモ: ..." のような
                // コロンを含む文章を含む）テキストとして共有してフォールバックする。
                else -> {
                    val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
                    if (appRepository.canHandleIntent(viewIntent)) {
                        Intent(Intent.ACTION_VIEW, uri)
                    } else {
                        createTextShareIntent(qrCode)
                    }
                }
            }
        }
    }

    private fun createWebIntent(uri: Uri): Intent {
        val host = uri.host?.lowercase() ?: ""
        return when (val rule = domainRules.find { it.matchHost(host) }?.rule) {
            is DomainRule.OpenInApp -> createAppIntent(uri, rule.packageName, rule.fallbackPackage)
            DomainRule.OpenInBrowser -> createBrowserIntent(uri)
            null -> Intent(Intent.ACTION_VIEW, uri)
        }
    }

    private fun looksLikeWebUrl(text: String): Boolean {
        return text.isNotBlank() &&
                !text.contains(Regex("\\s")) &&
                Patterns.WEB_URL.matcher(text).matches()
    }

    private fun createTextShareIntent(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

    // intent: スキーム URL（Android Intent URI）を解釈する。
    // 任意コンポーネントの直接起動を防ぐため component/selector を除去し、
    // ブラウザと同様に BROWSABLE カテゴリに限定する。
    private fun createIntentSchemeIntent(uri: String): Intent? {
        return try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                // URI 由来の権限付与フラグは安全のため落とす
                flags = flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION).inv()
            }.takeIf { appRepository.canHandleIntent(it) }
        } catch (e: Exception) {
            null
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
