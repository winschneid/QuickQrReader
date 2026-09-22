package com.ks.app.quickqrreader.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * ML Kit が返したバーコードの内容を文字列にする。
 *
 * `Barcode.rawValue` は内容を UTF-8 として解釈できなかったとき null になる。
 * 日本国内の QR コードは Shift_JIS で焼かれているものが珍しくなく、その場合
 * アプリは「データがありません」で諦めていた。`rawValue` が空のときに限り
 * `rawBytes` を別の文字コードとして解釈し直す。
 *
 * **`rawValue` が取れている場合は必ずそれをそのまま返す。** 読めている QR の
 * 解釈を後から書き換えると、今まで動いていた読み取りを壊しかねないため。
 */
object QrTextDecoder {

    // rawValue が null のとき試す文字コード。先頭から順に試し、
    // 1バイトも取りこぼさずに解釈できた最初のものを採用する。
    private val fallbackCharsets: List<Charset> = listOfNotNull(
        charsetOrNull("UTF-8"),
        // Shift_JIS の拡張（NEC/IBM 拡張文字を含む）。国内の QR はこちらで解釈できるものが多い。
        charsetOrNull("windows-31j"),
        charsetOrNull("Shift_JIS")
    )

    /**
     * @param rawValue ML Kit が解釈できた文字列。解釈できなければ null。
     * @param rawBytes バーコードの生バイト列。端末や読み取り経路によっては null。
     * @return 表示・処理に使える文字列。どの文字コードでも妥当に解釈できなければ null。
     */
    fun decode(rawValue: String?, rawBytes: ByteArray?): String? {
        if (!rawValue.isNullOrEmpty()) return rawValue

        val bytes = rawBytes?.takeIf { it.isNotEmpty() } ?: return null
        return fallbackCharsets
            .asSequence()
            .mapNotNull { decodeStrictly(bytes, it) }
            .firstOrNull { isPlausibleText(it) }
    }

    // 不正なバイト列を「?」や U+FFFD で埋めずに失敗させる。
    // 緩く解釈すると、どの文字コードでも「それらしい」文字列ができてしまい選別できない。
    private fun decodeStrictly(bytes: ByteArray, charset: Charset): String? =
        try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }

    // 文字コードとして成立していても、制御文字だらけならバイナリを取り違えている。
    // ユーザーに見せても Intent に載せても意味がないので採用しない。
    private fun isPlausibleText(text: String): Boolean =
        text.isNotEmpty() && text.none { it.isUnacceptableControlChar() }

    private fun Char.isUnacceptableControlChar(): Boolean =
        Character.isISOControl(this) && this != '\t' && this != '\n' && this != '\r'

    private fun charsetOrNull(name: String): Charset? =
        try {
            Charset.forName(name)
        } catch (e: Exception) {
            // 端末によっては未サポート。その文字コードを飛ばすだけで動作に影響はない。
            null
        }
}
