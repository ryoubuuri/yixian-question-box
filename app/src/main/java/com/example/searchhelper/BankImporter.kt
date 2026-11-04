package com.example.searchhelper

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Question bank file reader.
 *
 * A file is read into a plain row/column [TableData] first, then the caller decides which
 * column is the question and which is the answer (see [guessColumns] + import preview UI).
 *
 * Supported: .xlsx (first worksheet), .csv / .txt (delimiter auto-detected).
 * Excel on Windows saves CSV as GBK, so decoding falls back to GBK if not valid UTF-8.
 */
object BankImporter {

    /** Raw table: rows of trimmed cells. Short rows are simply shorter lists. */
    class TableData(
        val rows: List<List<String>>, val columnCount: Int,
        val structured: List<BankQuestion>? = null, val notices: List<String> = emptyList()
    )

    class ColumnGuess(val question: Int, val answer: Int, val hasHeader: Boolean)

    private const val MAX_COLUMNS = 16

    private val QUESTION_WORDS = listOf("题目", "题干", "问题", "question", "stem", "title")
    private val ANSWER_WORDS = listOf("答案", "正确选项", "正确答案", "选项答案", "answer", "result", "key", "正确")

    // ---------------------------------------------------------------- entry

    fun readTable(context: Context, uri: Uri): TableData {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 16 * 1024 * 1024) { "文件超过 16 MiB，请分批导入" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
            ?: throw IllegalArgumentException("无法读取所选文件")
        if (bytes.isEmpty()) throw IllegalArgumentException("所选文件为空")
        // XLSX is a zip package; detect by magic number because content URIs from SAF
        // usually have no usable file extension.
        return if (bytes.size > 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            readXlsxTable(bytes)
        } else if (decode(bytes).trimStart().startsWith("{")) {
            readJsonLines(bytes)
        } else {
            readCsvTable(bytes)
        }
    }

    fun readJsonLines(bytes: ByteArray): TableData {
        val questions = ArrayList<BankQuestion>()
        val notices = ArrayList<String>()
        decode(bytes).lineSequence().forEachIndexed { index, line ->
            if (line.isNotBlank()) {
                try { questions.add(QuestionBank.decode(JSONObject(line))) }
                catch (error: Exception) { notices.add("第 ${index + 1} 行：${error.message}") }
            }
        }
        require(questions.isNotEmpty()) { "没有有效 JSONL 题目。${notices.firstOrNull().orEmpty()}" }
        return TableData(questions.map { listOf(it.question, it.answer) }, 2, questions, notices)
    }

    // ---------------------------------------------------------------- CSV / TXT

    fun readCsvTable(bytes: ByteArray): TableData {
        val text = decode(bytes)
        val lines = ArrayList<String>()
        val record = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"') {
                record.append(ch)
                if (quoted && i + 1 < text.length && text[i + 1] == '"') { record.append('"'); i++ }
                else quoted = !quoted
            } else if ((ch == '\n' || ch == '\r') && !quoted) {
                if (record.isNotBlank()) lines.add(record.toString())
                record.setLength(0)
                if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            } else record.append(ch)
            i++
        }
        require(!quoted) { "CSV 引号未闭合，请检查文件" }
        if (record.isNotBlank()) lines.add(record.toString())
        if (lines.isEmpty()) return TableData(emptyList(), 0)
        val delimiter = detectDelimiter(lines.first())
        val rows = lines.map { splitLine(it, delimiter).map(String::trim).take(MAX_COLUMNS) }
        val columnCount = rows.maxOfOrNull { it.size } ?: 0
        return TableData(rows, columnCount)
    }

    /** UTF-8 first (strict), GBK as fallback for Excel-exported CSV. */
    private fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val decoder = Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("GBK"))
        }.removePrefix("\uFEFF")
    }

    private fun detectDelimiter(sample: String): Char {
        val comma = sample.count { it == ',' }
        val tab = sample.count { it == '\t' }
        val semicolon = sample.count { it == ';' }
        return when {
            tab > comma && tab > semicolon -> '\t'
            semicolon > comma -> ';'
            else -> ','
        }
    }

    /** CSV split with double-quote support. */
    private fun splitLine(line: String, delimiter: Char): List<String> {
        val result = ArrayList<String>(2)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == delimiter && !inQuotes -> {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    // ---------------------------------------------------------------- XLSX

    /**
     * XLSX is a zip: xl/sharedStrings.xml holds the string table, xl/worksheets/sheet1.xml
     * holds the cells. Two passes so the string table is available regardless of entry order.
     */
    private fun readXlsxTable(bytes: ByteArray): TableData {
        val shared = readSharedStrings(bytes)
        return readFirstSheet(bytes, shared)
    }

    private fun readSharedStrings(packageBytes: ByteArray): List<String> {
        val bytes = readZipEntry(packageBytes) { it == "xl/sharedStrings.xml" } ?: return emptyList()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        val strings = ArrayList<String>()
        val sb = StringBuilder()
        var inSi = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return strings
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        inSi = true
                        sb.setLength(0)
                    }
                    "t" -> if (inSi) sb.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si" && inSi) {
                    strings.add(sb.toString())
                    inSi = false
                }
            }
        }
    }

    private fun readFirstSheet(packageBytes: ByteArray, shared: List<String>): TableData {
        var fallback: ByteArray? = null
        ZipInputStream(packageBytes.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.startsWith("xl/worksheets/") || !entry.name.endsWith(".xml")) continue
                val bytes = zip.readBytes()
                if (entry.name == "xl/worksheets/sheet1.xml") return parseSheet(bytes, shared)
                if (fallback == null) fallback = bytes
            }
        }
        val data = fallback ?: return TableData(emptyList(), 0)
        return parseSheet(data, shared)
    }

    private fun parseSheet(data: ByteArray, shared: List<String>): TableData {
        val parser = Xml.newPullParser()
        parser.setInput(data.inputStream(), "UTF-8")
        val rows = ArrayList<List<String>>()
        var cells: HashMap<Int, String>? = null
        var autoIndex = 0
        var colIndex = -1
        var cellType = ""
        var lastTag = ""
        var columnCount = 0

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return TableData(rows, columnCount)
                XmlPullParser.START_TAG -> {
                    lastTag = parser.name
                    when (parser.name) {
                        "row" -> {
                            cells = HashMap()
                            autoIndex = 0
                        }
                        "c" -> {
                            val resolved = columnIndex(parser.getAttributeValue(null, "r"))
                            colIndex = if (resolved >= 0) resolved else autoIndex
                            autoIndex = colIndex + 1
                            cellType = parser.getAttributeValue(null, "t").orEmpty()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val current = cells
                    if (current != null && colIndex in 0 until MAX_COLUMNS &&
                        (lastTag == "v" || lastTag == "t")
                    ) {
                        val raw = parser.text
                        val value = if (lastTag == "v" && cellType == "s") {
                            shared.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty()
                        } else {
                            raw
                        }
                        current[colIndex] = (current[colIndex].orEmpty()) + value
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "row") {
                        val current = cells
                        if (current != null && current.isNotEmpty()) {
                            val width = minOf((current.keys.maxOrNull() ?: -1) + 1, MAX_COLUMNS)
                            val row = ArrayList<String>(width)
                            for (i in 0 until width) row.add(current[i]?.trim().orEmpty())
                            if (row.any { it.isNotEmpty() }) {
                                rows.add(row)
                                if (width > columnCount) columnCount = width
                            }
                        }
                        cells = null
                        colIndex = -1
                    }
                    lastTag = ""
                }
            }
        }
    }

    /** "AB12" -> 27 (0-based column index). */
    private fun columnIndex(reference: String?): Int {
        if (reference.isNullOrEmpty()) return -1
        var index = 0
        for (ch in reference) {
            if (ch in 'A'..'Z') {
                index = index * 26 + (ch - 'A' + 1)
            } else {
                break
            }
        }
        return if (index == 0) -1 else index - 1
    }

    private fun readZipEntry(packageBytes: ByteArray, match: (String) -> Boolean): ByteArray? {
        ZipInputStream(packageBytes.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (match(entry.name)) return zip.readBytes()
            }
        }
    }

    // ---------------------------------------------------------------- column picking

    /** "第3列：题干" style labels for the picker spinners. */
    fun columnLabels(data: TableData): List<String> {
        return (0 until data.columnCount).map { index ->
            val sample = data.rows
                .firstOrNull { it.getOrNull(index)?.isNotBlank() == true }
                ?.get(index)
                ?.replace('\n', ' ')
                ?.take(14)
                .orEmpty()
            "第${index + 1}列：$sample"
        }
    }

    /** First few rows, so the user can verify the mapping before importing. */
    fun sampleText(data: TableData, maxRows: Int = 3): String {
        val sb = StringBuilder()
        data.rows.take(maxRows).forEachIndexed { index, row ->
            sb.append("行").append(index + 1).append("：")
            sb.append(row.take(6).joinToString(" | ") { it.take(14) })
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * Best-effort default for the picker: header keywords first, otherwise
     * longest-average-length column = question, shortest other column = answer.
     */
    fun guessColumns(data: TableData): ColumnGuess {
        if (data.columnCount <= 1) return ColumnGuess(0, 0, false)

        val first = data.rows.firstOrNull().orEmpty()
        var question = -1
        var answer = -1
        first.forEachIndexed { index, text ->
            val lower = text.lowercase().replace(" ", "")
            if (question < 0 && QUESTION_WORDS.any { lower.contains(it) }) question = index
            if (answer < 0 && ANSWER_WORDS.any { lower.contains(it) }) answer = index
        }
        if (question >= 0 && answer >= 0 && question != answer) {
            return ColumnGuess(question, answer, true)
        }

        val sample = data.rows.take(20)
        val averages = (0 until data.columnCount).map { column ->
            val values = sample.mapNotNull { it.getOrNull(column) }.filter { it.isNotBlank() }
            if (values.isEmpty()) 0.0 else values.map { it.length }.average()
        }
        val questionColumn = averages.indices.maxByOrNull { averages[it] } ?: 0
        val answerColumn = averages.indices
            .filter { it != questionColumn }
            .minByOrNull { averages[it] } ?: 1
        return ColumnGuess(questionColumn, answerColumn, false)
    }

    /** Whether the first row looks like a header (used for the built-in CSV). */
    fun hasHeaderRow(data: TableData): Boolean {
        val joined = data.rows.firstOrNull()?.joinToString("")?.lowercase() ?: return false
        return QUESTION_WORDS.any { joined.contains(it) } && ANSWER_WORDS.any { joined.contains(it) }
    }

    /** Extract (question, answer) pairs using the selected columns. */
    fun toPairs(
        data: TableData,
        questionColumn: Int,
        answerColumn: Int,
        hasHeader: Boolean
    ): List<Pair<String, String>> {
        val pairs = ArrayList<Pair<String, String>>(data.rows.size)
        data.rows.forEachIndexed { index, row ->
            if (hasHeader && index == 0) return@forEachIndexed
            val question = row.getOrNull(questionColumn)?.trim().orEmpty()
            val answer = row.getOrNull(answerColumn)?.trim().orEmpty()
            if (question.isEmpty() || answer.isEmpty()) return@forEachIndexed
            if (question.startsWith("#")) return@forEachIndexed
            pairs.add(question to answer)
        }
        return pairs
    }

    fun toQuestions(data: TableData, questionColumn: Int, answerColumn: Int, hasHeader: Boolean): List<BankQuestion> {
        data.structured?.let { return it }
        val header = if (hasHeader) data.rows.firstOrNull().orEmpty() else emptyList()
        val optionColumns = header.indices.filter { index ->
            index != questionColumn && index != answerColumn &&
                Regex("(?:选项\\s*[1-8A-Ha-h]|[A-Ha-h]|option\\s*[1-8A-Ha-h])").matches(header[index].trim())
        }
        val explanationColumn = header.indexOfFirst { it.trim() in listOf("解析", "explanation") }
        return data.rows.mapIndexedNotNull { index, row ->
            if (hasHeader && index == 0) return@mapIndexedNotNull null
            val stem = row.getOrNull(questionColumn).orEmpty().trim()
            val answer = row.getOrNull(answerColumn).orEmpty().trim()
            if (stem.isBlank() || stem.startsWith("#") || answer.isBlank()) return@mapIndexedNotNull null
            val options = optionColumns.map { row.getOrNull(it).orEmpty().trim() }.dropLastWhile { it.isBlank() }
            try {
                BankQuestion(stem, answer, options, row.getOrNull(explanationColumn).orEmpty()).also { it.validate() }
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("第 ${index + 1} 行：${error.message}")
            }
        }
    }

    // ---------------------------------------------------------------- output

    fun toCsv(rows: List<Pair<String, String>>): String {
        val sb = StringBuilder(rows.size * 64)
        for ((question, answer) in rows) {
            sb.append(quote(question)).append(',').append(quote(answer)).append('\n')
        }
        return sb.toString()
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
