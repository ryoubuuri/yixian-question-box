package com.example.searchhelper

data class RankedQuestion(val entry: BankQuestion, val score: Double, val method: String)

sealed class BankDecision {
    data class Hit(val candidate: RankedQuestion, val screenLabels: List<String>) : BankDecision()
    data class Ambiguous(val candidates: List<RankedQuestion>, val reason: String) : BankDecision()
    object Miss : BankDecision()
}

/** Pure matching policy, shared by the app and JVM regression tests. */
object QuestionMatcher {
    fun find(stem: String, screenOptions: List<String>, entries: List<BankQuestion>): BankDecision {
        val query = QuestionText.normalize(stem)
        if (query.length < 4) return BankDecision.Miss
        val options = screenOptions.map { QuestionText.normalize(QuestionText.withoutOptionLabel(it)) }
        val ranked = entries.mapNotNull { entry ->
            val text = QuestionText.normalize(entry.question)
            if (text.length < 4) return@mapNotNull null
            val exact = text == query
            // A single changed number, identifier or negation can change the answer.
            if (!exact && (QuestionText.significantTokens(stem) != QuestionText.significantTokens(entry.question) ||
                    QuestionText.polarity(stem) != QuestionText.polarity(entry.question))) return@mapNotNull null
            val similarity = if (exact) 1.0 else dice(query, text)
            val threshold = if (minOf(query.length, text.length) < 12) 0.88 else 0.80
            if (similarity < threshold) return@mapNotNull null
            val bankOptions = entry.options.map { QuestionText.normalize(it) }
            if (options.isNotEmpty() && bankOptions.isNotEmpty() &&
                options.sorted() != bankOptions.sorted()) return@mapNotNull null
            RankedQuestion(entry, similarity, if (exact) "精确匹配" else "近似匹配")
        }.sortedByDescending { it.score }
        if (ranked.isEmpty()) return BankDecision.Miss
        val top = ranked.first()
        // Same stem / conflicting answer must remain visible rather than first-row wins.
        val close = ranked.filter { top.score - it.score < 0.08 }
        val distinctAnswers = close.map {
            it.entry.answerTexts.map(QuestionText::normalize).sorted()
        }.distinct()
        if (distinctAnswers.size > 1) return BankDecision.Ambiguous(close.take(5), "存在相似题或答案冲突，请核对原题")
        val labels = if (options.isNotEmpty() && top.entry.options.isNotEmpty()) {
            val indices = top.entry.answerTexts.map { answer ->
                options.indices.filter { options[it] == QuestionText.normalize(answer) }
            }
            if (indices.any { it.size != 1 }) return BankDecision.Ambiguous(close.take(5), "选项无法唯一对应，请核对答案正文")
            indices.map { ('A' + it.single()).toString() }
        } else emptyList()
        return BankDecision.Hit(top, labels)
    }

    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val counts = a.windowed(2).groupingBy { it }.eachCount().toMutableMap()
        var hits = 0
        b.windowed(2).forEach { gram ->
            val left = counts[gram] ?: 0
            if (left > 0) { hits++; counts[gram] = left - 1 }
        }
        return 2.0 * hits / (a.length + b.length - 2)
    }
}
