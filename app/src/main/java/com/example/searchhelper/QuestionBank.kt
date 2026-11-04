package com.example.searchhelper

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Structured local records; legacy CSV is read until the first successful v2 save. */
object QuestionBank {
    private const val FILE = "imported_bank_v2.jsonl"
    private const val LEGACY = "imported_bank.csv"
    @Volatile private var entries: List<BankQuestion> = emptyList()
    var builtInCount = 0; private set
    var importedCount = 0; private set
    val size: Int get() = entries.size

    @Synchronized fun loadAll(context: Context) {
        val builtIn = context.assets.open("question_bank.csv").use {
            val table = BankImporter.readCsvTable(it.readBytes())
            BankImporter.toQuestions(table, 0, 1, BankImporter.hasHeaderRow(table))
                .map { q -> q.copy(source = "内置题库") }
        }
        val imported = readImported(context)
        entries = (builtIn + imported).distinct()
        builtInCount = builtIn.distinct().size
        importedCount = entries.size - builtInCount
    }

    fun match(stem: String, options: List<String> = emptyList()): BankDecision =
        QuestionMatcher.find(stem, options, entries)

    @Synchronized fun saveImported(context: Context, questions: List<BankQuestion>, append: Boolean): Int {
        require(questions.isNotEmpty()) { "没有有效题目，请检查文件和列选择" }
        questions.forEach { it.validate() }
        val merged = ((if (append) readImported(context) else emptyList()) + questions).distinct()
        val atomic = AtomicFile(File(context.filesDir, FILE))
        val stream = atomic.startWrite()
        try {
            merged.forEach { stream.write((encode(it).toString() + "\n").toByteArray(Charsets.UTF_8)) }
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        loadAll(context)
        return size
    }

    @Synchronized fun clearImported(context: Context) {
        AtomicFile(File(context.filesDir, FILE)).delete()
        check(!File(context.filesDir, LEGACY).exists() || File(context.filesDir, LEGACY).delete()) { "旧题库删除失败" }
        loadAll(context)
    }

    fun hasImported(context: Context): Boolean =
        File(context.filesDir, FILE).exists() || File(context.filesDir, LEGACY).exists()

    private fun readImported(context: Context): List<BankQuestion> {
        val file = File(context.filesDir, FILE)
        if (file.exists()) return AtomicFile(file).openRead().bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { decode(JSONObject(it)) }.toList()
        }
        val legacy = File(context.filesDir, LEGACY)
        if (!legacy.exists()) return emptyList()
        val table = BankImporter.readCsvTable(legacy.readBytes())
        return BankImporter.toQuestions(table, 0, 1, BankImporter.hasHeaderRow(table))
    }

    internal fun encode(q: BankQuestion): JSONObject = JSONObject().apply {
        put("q", q.question); put("ans", q.answer); put("a", JSONArray(q.options))
        put("explanation", q.explanation); put("source", q.source)
    }

    internal fun decode(json: JSONObject): BankQuestion {
        val array = json.optJSONArray("a") ?: JSONArray()
        return BankQuestion(
            json.getString("q"), json.getString("ans"),
            (0 until array.length()).map { array.getString(it) },
            json.optString("explanation", ""), json.optString("source", "导入题库")
        ).also { it.validate() }
    }
}
