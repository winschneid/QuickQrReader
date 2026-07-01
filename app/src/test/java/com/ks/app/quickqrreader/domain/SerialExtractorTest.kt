package com.ks.app.quickqrreader.domain

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Rect の実際のフィールド値が必要なため Robolectric 上で実行する。
// (返り値をデフォルト化する通常のユニットテスト環境では Rect のコンストラクタが no-op になり、
// 座標が常に 0 になってしまうため)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SerialExtractorTest {

    private fun mockLine(text: String, boundingBox: Rect?): Text.Line {
        val line = mock<Text.Line>()
        whenever(line.text).thenReturn(text)
        whenever(line.boundingBox).thenReturn(boundingBox)
        return line
    }

    private fun mockText(vararg lines: Text.Line): Text {
        val block = mock<Text.TextBlock>()
        whenever(block.lines).thenReturn(lines.toList())
        val text = mock<Text>()
        whenever(text.textBlocks).thenReturn(listOf(block))
        return text
    }

    @Test
    fun `extract with label and value on same row in separate lines should return value`() {
        val labelLine = mockLine("応募シリアル", Rect(0, 100, 200, 140))
        val valueLine = mockLine("mpk39838", Rect(220, 100, 400, 140))
        val text = mockText(labelLine, valueLine)

        assertEquals("mpk39838", SerialExtractor.extract(text))
    }

    @Test
    fun `extract with label and value merged into single line should return value`() {
        val labelLine = mockLine("応募シリアル mpk39838", Rect(0, 100, 400, 140))
        val text = mockText(labelLine)

        assertEquals("mpk39838", SerialExtractor.extract(text))
    }

    @Test
    fun `extract when value is to the left of the label should be ignored`() {
        val valueLine = mockLine("mpk39838", Rect(0, 100, 180, 140))
        val labelLine = mockLine("応募シリアル", Rect(200, 100, 400, 140))
        val text = mockText(valueLine, labelLine)

        assertNull(SerialExtractor.extract(text))
    }

    @Test
    fun `extract when value is on a different row should be ignored`() {
        val labelLine = mockLine("応募シリアル", Rect(0, 100, 200, 140))
        val valueLine = mockLine("mpk39838", Rect(220, 500, 400, 540))
        val text = mockText(labelLine, valueLine)

        assertNull(SerialExtractor.extract(text))
    }

    @Test
    fun `extract when label is not found should return null`() {
        val line = mockLine("チケット抽選申込", Rect(0, 100, 200, 140))
        val text = mockText(line)

        assertNull(SerialExtractor.extract(text))
    }
}
