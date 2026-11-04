package com.example.searchhelper

import java.text.Normalizer
import java.util.Locale

/** Imported letters refer to this record's option order, never the screen's order. */
data class BankQuestion(
    val question: String,
    val answer: String,
    val options: List<String> = emptyList(),
    val explanation: String = "",
    val source: String = "导入题库"
) {
    val answerIndices: List<Int>
        get() = if (options.isNotEmpty() && answer.trim().uppercase(Locale.ROOT).matches(Regex("[A-H]+"))) {
            answer.trim().uppercase(Locale.ROOT).map { it - 'A' }.distinct()
        } else emptyList()

    val answerTexts: List<String>
        get() = if (answerIndices.isNotEmpty()) answerIndices.mapNotNull { options.getOrNull(it) }
        else listOf(answer.trim())

    fun validate() {
        require(question.isNotBlank()) { "题干不能为空" }
        require(answer.isNotBlank()) { "答案不能为空" }
        require(options.size <= 8 && options.none { it.isBlank() }) { "选项需为 1–8 个非空文本" }
        require(answerIndices.all { it in options.indices }) { "答案字母超出选项范围" }
    }
}

object QuestionText {
    fun withoutOptionLabel(text: String): String = text.trim().replace(
        Regex("^[（(]?[A-Ha-hＡ-Ｈ][.、．:：)）]\\s*"), ""
    )

    /** Preserve numbers, Latin identifiers, decimals and mathematical relations. */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('×', '*').replace('÷', '/').replace('−', '-')
        .filter { it.isLetterOrDigit() || it in ".+-*/%=<>^√!" }

    fun significantTokens(text: String): List<String> =
        Regex("[a-z]+|[0-9]+(?:\\.[0-9]+)?|[+*/%=<>^!\\-]")
            .findAll(normalize(text)).map { it.value }.toList()

    fun polarity(text: String): List<String> = Regex("不是|不能|不正确|不属于|错误|正确|除外|不|非|无需")
        .findAll(text).map { it.value }.toList()
}
