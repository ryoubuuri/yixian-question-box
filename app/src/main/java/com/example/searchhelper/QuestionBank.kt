package com.example.searchhelper

import android.content.Context
import java.io.File

data class BankMatch(val question: String, val answer: String)

/**
 * Question bank: built-in assets + user-imported file, offline matching.
 *
 * - Built-in: app/src/main/assets/question_bank.csv (shipped with the APK)
 * - Imported: filesDir/imported_bank.csv (written by [saveImported])
 *
 * Matching keys are HAN-ONLY: [normalize] drops every character that is not a CJK
 * ideograph (digits, latin letters, punctuation, brackets, spaces, option markers
 * such as A. / ① / (1) ...). Rationale: the bank is Chinese and OCR is unreliable
 * on non-Han glyphs, so comparing Han sequences alone removes the most common
 * recognition errors without needing a confusion table.
 *
 * Matching order: exact -> bidirectional containment -> bigram Dice similarity with
 * a length-adaptive threshold and a runner-up margin (ambiguous hits go to AI).
 */
object QuestionBank {

    private const val BUILT_IN_FILE = "question_bank.csv"
    private const val IMPORTED_FILE = "imported_bank.csv"

    /** A query must keep at least this many Han chars to be worth matching. */
    private const val MIN_QUERY_HAN = 4

    /** Only bank questions at least this long take part in containment matching. */
    private const val MIN_CONTAIN_HAN = 6

    /**
     * Minimum gap between the best and the runner-up Dice score. Closer than this
     * means the top hit is not trustworthy (two questions are equally similar), so
     * the caller should fall back to the AI rather than risk a wrong answer.
     */
    private const val DICE_MARGIN = 0.05

    private class Entry(val norm: String, val question: String, val answer: String)

    private val entries = ArrayList<Entry>()
    private val seen = HashSet<String>()

    /** Counts shown on the home screen. */
    var builtInCount = 0
        private set
    var importedCount = 0
        private set

    /** Call on app start: built-in bank first, then the imported one. */
    fun loadAll(context: Context) {
        entries.clear()
        seen.clear()
        builtInCount = 0
        importedCount = 0

        val builtIn = try {
            readBankCsv(context.assets.open(BUILT_IN_FILE).use { it.readBytes() })
        } catch (e: Exception) {
            emptyList()
        }
        builtIn.forEach { add(it.first, it.second) }
        builtInCount = entries.size

        val imported = readImported(context)
        imported.forEach { add(it.first, it.second) }
        importedCount = entries.size - builtInCount
    }

    val size: Int
        get() = entries.size

    /**
     * Persist imported pairs and reload.
     *
     * @param append true = merge into the existing bank (duplicates skipped),
     *               false = replace the imported part (built-in bank is kept).
     * @return total entry count after import.
     */
    fun saveImported(context: Context, pairs: List<Pair<String, String>>, append: Boolean): Int {
        if (pairs.isEmpty()) {
            throw IllegalArgumentException("按所选列未解析到任何题目，请检查列选择")
        }
        val file = File(context.filesDir, IMPORTED_FILE)
        if (!append) file.delete()
        file.appendText(BankImporter.toCsv(pairs), Charsets.UTF_8)
        loadAll(context)
        return entries.size
    }

    /** Delete everything brought in by imports; the built-in bank stays. */
    fun clearImported(context: Context) {
        File(context.filesDir, IMPORTED_FILE).delete()
        loadAll(context)
    }

    fun hasImported(context: Context): Boolean = File(context.filesDir, IMPORTED_FILE).exists()

    private fun readImported(context: Context): List<Pair<String, String>> {
        val file = File(context.filesDir, IMPORTED_FILE)
        if (!file.exists()) return emptyList()
        return try {
            readBankCsv(file.readBytes())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Stored banks are always two columns; drop the header row if present. */
    private fun readBankCsv(bytes: ByteArray): List<Pair<String, String>> {
        val data = BankImporter.readCsvTable(bytes)
        return BankImporter.toPairs(data, 0, 1, BankImporter.hasHeaderRow(data))
    }

    private fun add(question: String, answer: String) {
        val norm = normalize(question)
        if (norm.length < 2) return
        if (!seen.add(norm)) return
        entries.add(Entry(norm, question, answer))
    }

    /** @return matched question + answer, or null when nothing matched. */
    fun match(rawText: String): BankMatch? {
        val query = normalize(rawText)
        if (query.length < MIN_QUERY_HAN || entries.isEmpty()) return null

        for (entry in entries) {
            if (entry.norm == query) return BankMatch(entry.question, entry.answer)
        }

        // OCR text often contains the stem inside extra UI text, or drops a few
        // characters, so check containment in both directions before fuzzy matching.
        for (entry in entries) {
            if (entry.norm.length >= MIN_CONTAIN_HAN &&
                (query.contains(entry.norm) || entry.norm.contains(query))
            ) {
                return BankMatch(entry.question, entry.answer)
            }
        }

        // Bigram Dice with a length-adaptive threshold. Short Han strings lose more
        // similarity per OCR error than long ones, so short queries need a higher bar.
        var best: Entry? = null
        var bestScore = 0.0
        var runnerUpScore = 0.0
        for (entry in entries) {
            val score = dice(query, entry.norm)
            if (score > bestScore) {
                runnerUpScore = bestScore
                bestScore = score
                best = entry
            } else if (score > runnerUpScore) {
                runnerUpScore = score
            }
        }
        val hit = best ?: return null
        if (bestScore < thresholdFor(query.length)) return null
        if (bestScore - runnerUpScore < DICE_MARGIN) return null
        return BankMatch(hit.question, hit.answer)
    }

    /** Higher bars for short queries: one wrong Han char moves the score a lot. */
    private fun thresholdFor(hanLength: Int): Double = when {
        hanLength < 10 -> 0.72
        hanLength < 25 -> 0.66
        else -> 0.60
    }

    /**
     * Keep ONLY Han characters; drop everything else.
     *
     * Filtering rules / boundaries:
     * - Kept: CJK Unified Ideographs (U+4E00-U+9FFF) and Extension A (U+3400-U+4DBF).
     *   Surrogate-pair extensions (B+) are out of scope: they are essentially never
     *   used by this kind of Chinese safety question bank.
     * - Dropped: ASCII + full-width digits and letters, Latin punctuation, CJK
     *   punctuation, whitespace, brackets, option markers (A. B、① (1) ...), emoji.
     * - English/math/code-only questions normalize to empty -> they never match the
     *   bank and simply fall through to the AI path, which still sees the raw text.
     * - Two different questions that differ ONLY in digits/symbols (e.g. "第3条" vs
     *   "第5条") are indistinguishable after filtering -> the earlier CSV row wins on
     *   dedupe. Such banks should spell numbers in Han (第三/第五) to stay distinct.
     */
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            if ((code in 0x3400..0x4DBF) || (code in 0x4E00..0x9FFF)) sb.append(ch)
        }
        return sb.toString()
    }

    /** Dice coefficient over character bigrams. */
    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val counts = HashMap<String, Int>(a.length)
        for (i in 0 until a.length - 1) {
            val gram = a.substring(i, i + 2)
            counts[gram] = (counts[gram] ?: 0) + 1
        }
        var hits = 0
        for (i in 0 until b.length - 1) {
            val gram = b.substring(i, i + 2)
            val left = counts[gram] ?: 0
            if (left > 0) {
                hits++
                counts[gram] = left - 1
            }
        }
        return 2.0 * hits / ((a.length - 1) + (b.length - 1))
    }
}
