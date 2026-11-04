package com.example.searchhelper

import org.junit.Assert.*
import org.junit.Test

class QuestionMatcherTest {
    private val stem = "玄星实验蓝色晶体遇到温水变成什么颜色"
    private val q = BankQuestion(stem, "B", listOf("赤色", "翠色", "紫色", "白色"))

    @Test fun reorderedOptionsReturnCurrentLetterAndOriginalContent() {
        val hit = QuestionMatcher.find(stem, listOf("A.紫色", "B.白色", "C.赤色", "D.翠色"), listOf(q)) as BankDecision.Hit
        assertEquals(listOf("D"), hit.screenLabels)
        assertEquals(listOf("翠色"), hit.candidate.entry.answerTexts)
    }
    @Test fun changedNumbersNeverCollapseOrFuzzyMatch() {
        val q = BankQuestion("第3条规定的测试标记是什么", "甲")
        assertTrue(QuestionMatcher.find("第5条规定的测试标记是什么", emptyList(), listOf(q)) is BankDecision.Miss)
        assertNotEquals(QuestionText.normalize(q.question), QuestionText.normalize("第5条规定的测试标记是什么"))
    }
    @Test fun formulasAndLatinIdentifiersRemainMeaningful() {
        assertNotEquals(QuestionText.normalize("x < 0"), QuestionText.normalize("x > 0"))
        assertNotEquals(QuestionText.normalize("AX17"), QuestionText.normalize("AX18"))
        assertEquals("x<0", QuestionText.normalize("ｘ ＜ ０"))
        assertTrue(QuestionMatcher.find("Which token is AX17", emptyList(), listOf(BankQuestion("Which token is AX17", "north"))) is BankDecision.Hit)
    }
    @Test fun negationCannotBeFuzzilyIgnored() {
        assertTrue(QuestionMatcher.find("以下哪些材料不是实验指定材料", emptyList(),
            listOf(BankQuestion("以下哪些材料是实验指定材料", "甲"))) is BankDecision.Miss)
    }
    @Test fun conflictingAnswersNeverUseFirstRowWins() {
        val conflict = q.copy(answer = "D")
        assertTrue(QuestionMatcher.find(stem, emptyList(), listOf(q, conflict)) is BankDecision.Ambiguous)
        assertTrue(QuestionMatcher.find(stem, emptyList(), listOf(conflict, q)) is BankDecision.Ambiguous)
    }
    @Test fun shortSubstringDoesNotAutoSelectFirstCandidate() {
        assertTrue(QuestionMatcher.find("蓝色晶体", emptyList(), listOf(q)) is BankDecision.Miss)
    }
    @Test fun mismatchedOptionsDoNotProduceLocalAnswer() {
        assertTrue(QuestionMatcher.find(stem, listOf("A.甲", "B.乙"), listOf(q)) is BankDecision.Miss)
    }
    @Test fun duplicateScreenChoicesAreAmbiguous() {
        val repeated = q.copy(options = listOf("赤色", "翠色", "翠色", "白色"))
        assertTrue(QuestionMatcher.find(stem, repeated.options, listOf(repeated)) is BankDecision.Ambiguous)
    }
    @Test fun multiChoiceMapsEachAnswerByContent() {
        val multi = q.copy(answer = "AC")
        val hit = QuestionMatcher.find(stem, listOf("紫色", "白色", "赤色", "翠色"), listOf(multi)) as BankDecision.Hit
        assertEquals(listOf("C", "A"), hit.screenLabels)
    }
    @Test fun invalidLetterIsRejected() {
        try { q.copy(answer = "G").validate(); fail("Expected invalid answer") }
        catch (_: IllegalArgumentException) { }
    }
}
