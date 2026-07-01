package com.ks.app.quickqrreader.domain

import com.google.mlkit.vision.text.Text

/**
 * OCR で認識したテキストから「応募シリアル」欄の値を抽出するオプション機能。
 * 見つからない場合は null を返すのみで、呼び出し側（QRコード読み取り）には一切影響しない。
 */
object SerialExtractor {
    private val labelRegex = Regex("シリアル")
    private val valueRegex = Regex("^[A-Za-z0-9]{4,}$")
    // ラベルと値が同一行としてOCR認識された場合のフォールバック（例: "応募シリアル mpk39838"）
    private val inlineValueRegex = Regex("シリアル[^A-Za-z0-9]*([A-Za-z0-9]{4,})")

    fun extract(text: Text): String? {
        val lines = text.textBlocks.flatMap { it.lines }
        val labelLine = lines.firstOrNull { labelRegex.containsMatchIn(it.text) } ?: return null

        return extractFromSameRow(lines, labelLine)
            ?: inlineValueRegex.find(labelLine.text)?.groupValues?.get(1)
    }

    // 表形式のレイアウトを想定し、ラベルと同じ行（縦位置が重なる）かつ
    // ラベルより右側にある行から、英数字のみの値を探す。
    private fun extractFromSameRow(lines: List<Text.Line>, labelLine: Text.Line): String? {
        val labelBox = labelLine.boundingBox ?: return null

        return lines
            .asSequence()
            .filter { it !== labelLine }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val verticalCenter = (box.top + box.bottom) / 2
                if (verticalCenter !in labelBox.top..labelBox.bottom) return@mapNotNull null
                if (box.left < labelBox.right) return@mapNotNull null
                line.text.replace(Regex("\\s"), "")
            }
            .firstOrNull { valueRegex.matches(it) }
    }
}
