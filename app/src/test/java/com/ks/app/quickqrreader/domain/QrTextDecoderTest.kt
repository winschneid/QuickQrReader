package com.ks.app.quickqrreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class QrTextDecoderTest {

    private val shiftJis: Charset = Charset.forName("windows-31j")

    // --- rawValue が取れているときは絶対に書き換えない（既存の読み取りを壊さないため） ---

    @Test
    fun `returns rawValue as-is when ML Kit could decode it`() {
        assertEquals("https://example.com", QrTextDecoder.decode("https://example.com", null))
    }

    @Test
    fun `prefers rawValue over rawBytes even when both are present`() {
        val misleadingBytes = "別の文字列".toByteArray(shiftJis)
        assertEquals("https://example.com", QrTextDecoder.decode("https://example.com", misleadingBytes))
    }

    @Test
    fun `prefers rawValue even when rawBytes would decode to something else`() {
        // rawValue が非 ASCII でも、ML Kit が解釈できている以上それを信じる
        assertEquals("日本語", QrTextDecoder.decode("日本語", "ニホンゴ".toByteArray(shiftJis)))
    }

    // --- rawValue が空のときだけ rawBytes を解釈し直す ---

    @Test
    fun `decodes Shift_JIS bytes when rawValue is null`() {
        val bytes = "会員証はこちら".toByteArray(shiftJis)
        assertEquals("会員証はこちら", QrTextDecoder.decode(null, bytes))
    }

    @Test
    fun `decodes Shift_JIS half-width katakana when rawValue is null`() {
        val bytes = "ｼﾞｭｰｽ".toByteArray(shiftJis)
        assertEquals("ｼﾞｭｰｽ", QrTextDecoder.decode(null, bytes))
    }

    @Test
    fun `decodes a Shift_JIS URL with a Japanese query when rawValue is null`() {
        val text = "https://example.com/?q=検索"
        assertEquals(text, QrTextDecoder.decode(null, text.toByteArray(shiftJis)))
    }

    @Test
    fun `decodes UTF-8 bytes when rawValue is empty`() {
        val bytes = "こんにちは".toByteArray(Charsets.UTF_8)
        assertEquals("こんにちは", QrTextDecoder.decode("", bytes))
    }

    @Test
    fun `prefers UTF-8 over Shift_JIS for bytes valid in both`() {
        // ASCII のみならどちらでも同じ結果。順序に依存した取り違えが起きないことの確認。
        assertEquals("ABC123", QrTextDecoder.decode(null, "ABC123".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `keeps newlines and tabs in decoded text`() {
        val text = "名前\t山田\n所属\t営業部"
        assertEquals(text, QrTextDecoder.decode(null, text.toByteArray(shiftJis)))
    }

    // --- 解釈できないものは null のまま（誤った文字列を下流に流さない） ---

    @Test
    fun `returns null when both rawValue and rawBytes are missing`() {
        assertNull(QrTextDecoder.decode(null, null))
    }

    @Test
    fun `returns null for empty rawBytes`() {
        assertNull(QrTextDecoder.decode(null, ByteArray(0)))
    }

    @Test
    fun `returns null for binary payloads that are not text`() {
        // どの文字コードでも制御文字だらけになるバイト列
        val binary = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        assertNull(QrTextDecoder.decode(null, binary))
    }

    @Test
    fun `returns null rather than mangling bytes that decode to control characters`() {
        val withNul = byteArrayOf('O'.code.toByte(), 'K'.code.toByte(), 0x00)
        assertNull(QrTextDecoder.decode(null, withNul))
    }
}
