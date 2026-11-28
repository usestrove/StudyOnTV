package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** 把题目正确答案格式化成闪卡背面可直接阅读的答案文本。 */
internal fun formatAnswerDisplayImpl(question: JSONObject): String = when (question.optString("type", "single")) {
    "boolean" -> {
        val opts = question.optJSONArray("a")
        val k = question.optInt("k", 0)
        opts?.optString(k).orEmpty().ifEmpty { if (k == 0) "正确" else "错误" }
    }
    "multiple" -> {
        val opts = question.optJSONArray("a")
        val indices = question.optJSONArray("correctIndices") ?: JSONArray()
        (0 until indices.length()).map { i ->
            val k = indices.optInt(i)
            val label = ('A' + k).toString()
            val text = opts?.optString(k).orEmpty().trim()
            if (text.isEmpty()) label else "$label. $text"
        }.joinToString(" / ")
    }
    "text" -> {
        val answers = question.optJSONArray("answers") ?: JSONArray()
        (0 until answers.length()).map { answers.optString(it).trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
    }
    "subjective" -> question.optString("modelAnswer").trim()
    else -> {
        val opts = question.optJSONArray("a")
        val k = question.optInt("k", 0)
        val label = ('A' + k).toString()
        val text = opts?.optString(k).orEmpty().trim()
        if (text.isEmpty()) label else "$label. $text"
    }
}

private fun isCjkChar(c: Char): Boolean =
    c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF'

/**
 * 去掉两个汉字之间的空白字符。
 * 纯字符区间循环实现，避免 Android ICU 正则不支持 OpenJDK 的 `\p{IsHan}` 导致 `<clinit>` 闪退（ST-608）。
 */
internal fun collapseCjkSpacesImpl(text: String): String {
    if (text.isEmpty()) return ""
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isWhitespace()) {
            var j = i + 1
            while (j < text.length && text[j].isWhitespace()) j++
            val prevIsCjk = sb.isNotEmpty() && isCjkChar(sb[sb.length - 1])
            val nextIsCjk = j < text.length && isCjkChar(text[j])
            if (!(prevIsCjk && nextIsCjk)) {
                sb.append(text, i, j)
            }
            i = j
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

internal fun normalizeTextExactImpl(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

/**
 * 简答/填空判分归一化：排除自然语言标点，同时保留小数点与数学语义符号，避免误伤数值/公式答案。
 */
internal fun normalizeTextIgnoringPunctuationImpl(value: String): String {
    val base = normalizeTextExactImpl(value)
    if (base.isEmpty()) return ""
    val sb = StringBuilder(base.length)
    for (i in base.indices) {
        val c = base[i]
        val keepMath = c == '+' || c == '-' || c == '*' || c == '/' ||
            c == '%' || c == '=' || c == '<' || c == '>' || c == '^'
        val keepDecimal = c == '.' &&
            i > 0 && i + 1 < base.length &&
            base[i - 1].isDigit() && base[i + 1].isDigit()
        if (keepMath || keepDecimal) {
            sb.append(c)
            continue
        }
        val type = Character.getType(c).toByte()
        val isPunctuation = type == Character.DASH_PUNCTUATION ||
            type == Character.START_PUNCTUATION ||
            type == Character.END_PUNCTUATION ||
            type == Character.CONNECTOR_PUNCTUATION ||
            type == Character.OTHER_PUNCTUATION ||
            type == Character.INITIAL_QUOTE_PUNCTUATION ||
            type == Character.FINAL_QUOTE_PUNCTUATION
        sb.append(if (isPunctuation) ' ' else c)
    }
    return collapseCjkSpacesImpl(
        sb.toString()
            .trim()
            .replace(Regex("\\s+"), " ")
    )
}

internal fun matchesTextAnswerImpl(actual: String, expected: String): Boolean {
    val exactActual = normalizeTextExactImpl(actual)
    val exactExpected = normalizeTextExactImpl(expected)
    if (exactActual.isEmpty() || exactExpected.isEmpty()) return false
    if (exactActual == exactExpected) return true
    val strippedActual = normalizeTextIgnoringPunctuationImpl(actual)
    val strippedExpected = normalizeTextIgnoringPunctuationImpl(expected)
    return strippedActual.isNotEmpty() && strippedActual == strippedExpected
}
