package com.example.searchhelper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Runtime configuration persisted in SharedPreferences.
 *
 * Endpoint and model are ordinary settings and live in [PREFS_NAME].
 * The API key is a credential: it is kept in its own file encrypted with
 * [EncryptedSharedPreferences] (AES-256, master key held by the Android Keystore),
 * it is never logged, and it is masked whenever the UI shows it (see [maskedApiKey]).
 *
 * All three are read at request time, so saving takes effect on the very next search
 * without restarting the capture service.
 */
object Config {

    private const val PREFS_NAME = "search_helper"
    private const val SECURE_PREFS_NAME = "search_helper_secure"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_REGION_MODE = "region_mode"
    private const val KEY_REGION_RECT = "region_rect"

    /** Capture area behaviour; see [regionMode] + [lastRegionRect]. */
    const val REGION_MODE_FULL = "full"      // whole screen, the original behaviour
    const val REGION_MODE_SELECT = "select"  // pick an area on every search
    const val REGION_MODE_LAST = "last"      // reuse the previously saved area

    const val DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions"
    const val DEFAULT_MODEL = "deepseek-chat"

    /** Ready-made (endpoint, model) pairs offered by the settings spinner. */
    data class Preset(val label: String, val endpoint: String, val model: String)

    val PRESETS: List<Preset> = listOf(
        Preset("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat"),
        Preset("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        Preset("Kimi / Moonshot", "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k"),
        Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-flash"),
        Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus")
    )

    private val prefs: SharedPreferences by lazy {
        AppContext.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Credentials only. Encrypted at rest; kept apart from ordinary settings. */
    private val securePrefs: SharedPreferences by lazy {
        val context = AppContext.get()
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var apiKey: String
        get() = securePrefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = securePrefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()

    /** Safe-to-display form: only the last 4 characters are ever revealed. */
    fun maskedApiKey(): String {
        val key = apiKey
        if (key.isBlank()) return "未配置（题库命中时可不填）"
        return "已配置 ••••••••${key.takeLast(4)}（共 ${key.length} 位）"
    }

    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT).orEmpty()
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /** Restore endpoint + model to the built-in defaults. The API key is kept. */
    fun resetDefaults() {
        endpoint = DEFAULT_ENDPOINT
        model = DEFAULT_MODEL
    }

    // ------------------------------------------------------------- capture area

    /**
     * Which part of the screen is OCRed. One of [REGION_MODE_FULL], [REGION_MODE_SELECT],
     * [REGION_MODE_LAST]. Unknown persisted values fall back to FULL (forward compatible).
     */
    var regionMode: String
        get() = when (prefs.getString(KEY_REGION_MODE, REGION_MODE_FULL)) {
            REGION_MODE_SELECT -> REGION_MODE_SELECT
            REGION_MODE_LAST -> REGION_MODE_LAST
            else -> REGION_MODE_FULL
        }
        set(value) = prefs.edit().putString(KEY_REGION_MODE, value).apply()

    /**
     * Last user-selected capture area, stored as fractions of the screen size
     * (0..1, left/top/right/bottom). Fractions survive resolution changes, so the
     * same relative region is reused after rotation or on another device.
     * Persisted as "l,t,r,b" so it survives restarts.
     */
    var lastRegionRect: RectF?
        get() {
            val raw = prefs.getString(KEY_REGION_RECT, null) ?: return null
            val parts = raw.split(",")
            if (parts.size != 4) return null
            return try {
                val v = parts.map { it.toFloat() }
                RectF(v[0], v[1], v[2], v[3])
            } catch (e: NumberFormatException) {
                null
            }
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_REGION_RECT).apply()
            } else {
                val v = value
                prefs.edit().putString(
                    KEY_REGION_RECT,
                    "${v.left},${v.top},${v.right},${v.bottom}"
                ).apply()
            }
        }

    /** Drop the remembered region; LAST mode then falls back to the full screen. */
    fun clearLastRegion() {
        lastRegionRect = null
    }

    // ------------------------------------------------------------------ validation
    // Every check returns null when the value is acceptable, otherwise a readable
    // reason that is rendered right under the offending input box.

    /** @return error message, or null when [raw] is a usable URL. */
    fun checkEndpoint(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return "接口地址不能为空"
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            return "接口地址必须以 http:// 或 https:// 开头"
        }
        val host = Uri.parse(value).host
        if (host.isNullOrBlank()) {
            return "接口地址不完整，解析不到主机名（示例：https://api.deepseek.com/chat/completions）"
        }
        return null
    }

    /** @return error message, or null when [raw] is a usable model id. */
    fun checkModel(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return "模型名称不能为空"
        if (value.contains(Regex("\\s"))) return "模型名称不能包含空格或换行"
        return null
    }

    /**
     * @return error message, or null when [raw] is empty (the key is optional: a local
     * bank hit never needs one) or looks like a real credential.
     */
    fun checkApiKey(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.contains(Regex("\\s"))) return "API Key 不能包含空格或换行，请检查是否复制多了字符"
        if (value.length < 8) return "API Key 只有 ${value.length} 位，疑似复制不完整"
        return null
    }

    /** Index into [PRESETS] for the given pair, or -1 when it is a custom combination. */
    fun indexOfPreset(endpoint: String, model: String): Int {
        val targetEndpoint = endpoint.trim().trimEnd('/')
        val targetModel = model.trim()
        return PRESETS.indexOfFirst {
            it.endpoint.trimEnd('/').equals(targetEndpoint, ignoreCase = true) &&
                it.model.equals(targetModel, ignoreCase = true)
        }
    }
}
