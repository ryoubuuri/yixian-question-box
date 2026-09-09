package com.example.searchhelper

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OCR -> structured screen content -> question bank -> AI.
 *
 * [recognizeLines] keeps every OCR text line with its bounding box instead of
 * flattening the whole screen to a single string; [buildQuestion] then splits the
 * lines into a question stem, an option list and noise (see [isNoiseLine]).
 * Answers are resolved locally first (instant, offline); only a miss goes to the
 * remote Chat Completion endpoint, and the prompt carries the stem + options so the
 * model answers the actual question instead of UI chrome text.
 */
object QuestionSolver {

    private const val MAX_QUESTION_CHARS = 600
    private const val SYSTEM_PROMPT =
        "你是一个答题助手。只输出答案选项字母或简短结论，再附一句极简解析，不要客套。"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** One OCR text line with its position, top-to-bottom reading order matters. */
    data class OcrLine(val text: String, val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * What the solver needs: the clean question stem, the answer options (if any
     * were recognized) and the raw text of every kept line (fallback + AI source).
     */
    data class ScreenQuestion(val stem: String, val options: List<String>, val rawText: String)

    // ------------------------------------------------------------------ OCR

    /** Run ML Kit Chinese OCR and return the recognised lines, reading order. */
    suspend fun recognizeLines(bitmap: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = recognizer.process(image).await()
        text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text.trim(), box.left, box.top, box.width(), box.height())
            }
        }.sortedWith(compareBy({ it.y }, { it.x }))
    }

    /**
     * Turn OCR lines into a [ScreenQuestion].
     *
     * Layout / text-structure heuristics used here:
     * - Options: short lines that start with an option marker such as A. / A、/ ① / (1).
     * - Noise: UI chrome lines (上一题/下一题/计时/得分 ...) that must not pollute
     *   the stem nor the bank query — see [isNoiseLine].
     * - Stem: every kept line that is neither an option nor noise. Question and
     *   options are typically top-down, so the stem comes first in reading order.
     */
    fun buildQuestion(lines: List<OcrLine>): ScreenQuestion {
        val kept = ArrayList<OcrLine>(lines.size)
        val options = ArrayList<String>()
        for (line in lines) {
            if (isNoiseLine(line.text)) continue
            if (isOptionLine(line.text)) {
                options.add(line.text)
            } else {
                kept.add(line)
            }
        }
        val stem = kept.joinToString("\n") { it.text }.trim()
        val rawText = (kept.map { it.text } + options).joinToString("\n").trim()
        return ScreenQuestion(stem, options, rawText)
    }

    /** OCR a full bitmap straight into a ScreenQuestion (convenience for callers). */
    suspend fun analyze(bitmap: Bitmap): ScreenQuestion {
        return buildQuestion(recognizeLines(bitmap))
    }

    // ---------------------------------------------------------- line filters

    private val OPTION_PREFIX = Regex(
        "^[（(]?[A-Ha-hＡ-Ｈ][.、．:：)）]|^[①-⑩]|^[（(][0-9０-９]{1,2}[)）]"
    )

    /** e.g. "A．在潮湿场所…" / "① 安全帽" / "(2) 佩戴…" are answer options. */
    private fun isOptionLine(text: String): Boolean {
        if (text.length > 80) return false
        return OPTION_PREFIX.containsMatchIn(text)
    }

    private val CHROME_WORDS = listOf(
        "上一题", "下一题", "答题卡", "交卷", "倒计时", "剩余时间",
        "开始答题", "暂停答题", "重新答题", "得分", "正确率", "解析"
    )

    /**
     * True for UI chrome that has no question content. Note: "解析" is filtered only
     * as a bare short line (a "查看解析" button) — a question that literally asks
     * about 解析 is longer than 10 chars and therefore survives.
     */
    private fun isNoiseLine(text: String): Boolean {
        if (text.length <= 12 && CHROME_WORDS.any { text.contains(it) }) return true
        // "第 12 题 / 共 20 题", "3/20", timer "00:45" — pure progress indicators.
        if (Regex("第\\s*\\d+\\s*题|\\d+\\s*[／/]\\s*\\d+|\\d{1,2}:\\d{2}").containsMatchIn(text) &&
            text.length <= 12
        ) return true
        return false
    }

    // ------------------------------------------------------------------ solve

    /**
     * Resolve a question:
     * 1. local bank match on the clean stem only (digits/symbols already dropped by
     *    [QuestionBank.normalize], so OCR noise there cannot hurt),
     * 2. if that misses, match the raw text (kept lines + options) as a fallback,
     * 3. otherwise ask the AI, feeding it the stem and the recognised options.
     */
    suspend fun solve(screen: ScreenQuestion): String {
        val stem = screen.stem
        if (stem.isBlank()) return "未识别到题目文字，请调整屏幕内容或框选区域后重试"

        val local = QuestionBank.match(stem) ?: QuestionBank.match(screen.rawText)
        if (local != null) {
            return "【本地题库命中】\n题目：${local.question}\n\n答案：${local.answer}"
        }
        return askAi(screen)
    }

    /** Backwards-compatible entry point: an already-flattened OCR string. */
    suspend fun solve(ocrText: String): String {
        val text = ocrText.trim()
        if (text.isBlank()) return "未识别到文字，请调整屏幕内容后重试"
        return solve(ScreenQuestion(stem = text, options = emptyList(), rawText = text))
    }

    // ------------------------------------------------------------------ AI

    private suspend fun askAi(screen: ScreenQuestion): String = withContext(Dispatchers.IO) {
        val apiKey = Config.apiKey
        if (apiKey.isBlank()) {
            return@withContext "本地题库未命中，且未配置 AI 接口 Key。\n请在首页填写 Key 后重新启动悬浮窗。"
        }

        val prompt = StringBuilder(screen.stem.take(MAX_QUESTION_CHARS))
        if (screen.options.isNotEmpty()) {
            prompt.append("\n\n选项：\n").append(screen.options.joinToString("\n"))
        }

        val body = JSONObject().apply {
            put("model", Config.model)
            put("temperature", 0.1)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt.toString())
                })
            })
        }

        val request = Request.Builder()
            .url(Config.endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext "AI 接口错误 ${response.code}：$payload"
                val content = JSONObject(payload)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                return@withContext "【AI 解答】\n$content"
            }
        } catch (e: Exception) {
            return@withContext "AI 请求异常：${e.message}"
        }
    }

    // ------------------------------------------------------------------ crop

    /**
     * Crop [source] to [rect]. Invalid or degenerate rects (touch slop) yield the
     * original bitmap so a careless selection can never break recognition.
     */
    fun cropRegion(source: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, source.width)
        val top = rect.top.coerceIn(0, source.height)
        val right = rect.right.coerceIn(0, source.width)
        val bottom = rect.bottom.coerceIn(0, source.height)
        if (right - left < 24 || bottom - top < 24) return source
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    /**
     * Cheapest possible round-trip against the configured endpoint, used by the
     * settings screen【测试连接】button.
     *
     * @return a short success description. Throws with a readable reason otherwise, so
     * the caller can show the exact cause (bad key, wrong model, unreachable host...).
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val apiKey = Config.apiKey
        if (apiKey.isBlank()) throw IllegalStateException("未填写 API Key，无法测试")

        val body = JSONObject().apply {
            put("model", Config.model)
            put("max_tokens", 8)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "hi")
                })
            })
        }

        val request = Request.Builder()
            .url(Config.endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    401, 403 -> "Key 无效或没有该模型的权限"
                    404 -> "接口地址不存在，或该模型名在当前服务商下不可用"
                    429 -> "请求过于频繁或余额/额度不足"
                    else -> "请检查接口地址、Key 与模型名是否匹配"
                }
                throw IllegalStateException("HTTP ${response.code}（$hint）：${payload.take(200)}")
            }
            val content = JSONObject(payload)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            return@withContext "可用。模型 ${Config.model} 回复：${content.trim().take(40)}"
        }
    }
}
