package com.example.searchhelper

import org.junit.Assert.*
import org.junit.Test

class BankImporterTest {
    @Test fun jsonLinesReportBadRowsWithoutDiscardingGoodQuestions() {
        val input = """{"q":"测试题干数字3不能删除","a":["甲","乙"],"ans":"B"}
{not json}
{"q":"越界答案题目","a":["甲","乙"],"ans":"G"}
"""
        val data = BankImporter.readJsonLines(input.toByteArray())
        val questions = requireNotNull(data.structured)
        assertEquals(1, questions.size)
        assertEquals(listOf("乙"), questions.first().answerTexts)
        assertEquals(2, data.notices.size)
        assertTrue(data.notices[0].contains("第 2 行"))
        assertTrue(data.notices[1].contains("第 3 行"))
    }
    @Test fun csvRoundTripsQuotedMultilineFields() {
        val records = listOf("第一行\n第二行,包含逗号和\"引号\"" to "答案\n正文")
        val table = BankImporter.readCsvTable(BankImporter.toCsv(records).toByteArray())
        assertEquals(records, BankImporter.toPairs(table, 0, 1, false))
    }
    @Test fun csvHeaderExtractsOptionsAndExplanation() {
        val data = BankImporter.readCsvTable("题干,答案,选项1,选项2,解析\n测试题干,B,甲,乙,说明".toByteArray())
        val question = BankImporter.toQuestions(data, 0, 1, true).single()
        assertEquals(listOf("甲", "乙"), question.options)
        assertEquals(listOf("乙"), question.answerTexts)
        assertEquals("说明", question.explanation)
    }
    @Test fun structuredRecordsRoundTripWithSourceAndConflicts() {
        val first = BankQuestion("第3条规定是什么", "A", listOf("甲", "乙"), "解释", "自编题库")
        assertEquals(first, QuestionBank.decode(QuestionBank.encode(first)))
        assertNotEquals(first, first.copy(answer = "B"))
    }
}
