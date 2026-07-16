package com.aicontrol.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.aicontrol.app.ai.OpenAIClient

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── API ──────────────────────────────────────────────────────────────────

    /** مفتاح الـ API */
    var openAiApiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    /** مزود الخدمة: "huggingface" أو "openai" أو "google" */
    var apiProvider: String
        get() = prefs.getString(KEY_API_PROVIDER, PROVIDER_HF) ?: PROVIDER_HF
        set(value) = prefs.edit { putString(KEY_API_PROVIDER, value) }

    /** الـ base URL المستخدمة فعلياً حسب المزود */
    val apiBaseUrl: String
        get() = when (apiProvider) {
            PROVIDER_OPENAI  -> OpenAIClient.BASE_URL_OPENAI
            PROVIDER_GEMINI  -> OpenAIClient.BASE_URL_GEMINI
            else             -> OpenAIClient.BASE_URL_HUGGINGFACE
        }

    /** النموذج المختار */
    var selectedModel: String
        get() = prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL_HF) ?: DEFAULT_MODEL_HF
        set(value) = prefs.edit { putString(KEY_SELECTED_MODEL, value) }

    /** النموذج الافتراضي حسب المزود الحالي */
    val defaultModel: String
        get() = when (apiProvider) {
            PROVIDER_OPENAI -> DEFAULT_MODEL_OPENAI
            PROVIDER_GEMINI -> DEFAULT_MODEL_GEMINI
            else            -> DEFAULT_MODEL_HF
        }

    /** قائمة النماذج المتاحة حسب المزود الحالي */
    val currentModelList: Array<String>
        get() = when (apiProvider) {
            PROVIDER_OPENAI -> MODELS_OPENAI
            PROVIDER_GEMINI -> MODELS_GEMINI
            else            -> MODELS_HF
        }

    // ─── Task ────────────────────────────────────────────────────────────────

    var activeTaskId: Long
        get() = prefs.getLong(KEY_ACTIVE_TASK_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_ACTIVE_TASK_ID, value) }

    var isAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_AI_ENABLED, value) }

    // ─── Timing ──────────────────────────────────────────────────────────────

    var actionDelay: Int
        get() = prefs.getInt(KEY_ACTION_DELAY, 2000)
        set(value) = prefs.edit { putInt(KEY_ACTION_DELAY, value) }

    var maxActions: Int
        get() = prefs.getInt(KEY_MAX_ACTIONS, 50)
        set(value) = prefs.edit { putInt(KEY_MAX_ACTIONS, value) }

    // ─── Float position ──────────────────────────────────────────────────────

    var floatX: Int
        get() = prefs.getInt(KEY_FLOAT_X, 100)
        set(value) = prefs.edit { putInt(KEY_FLOAT_X, value) }

    var floatY: Int
        get() = prefs.getInt(KEY_FLOAT_Y, 300)
        set(value) = prefs.edit { putInt(KEY_FLOAT_Y, value) }

    /**
     * استخدام UI Tree بدلاً من الصورة (يوفّر ~99% من التوكنز).
     * مفعّل افتراضياً.
     */
    var useUITree: Boolean
        get() = prefs.getBoolean(KEY_USE_UI_TREE, true)
        set(value) = prefs.edit { putBoolean(KEY_USE_UI_TREE, value) }

    // ─── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val PREFS_NAME = "ai_control_prefs"

        private const val KEY_API_KEY        = "api_key"
        private const val KEY_API_PROVIDER   = "api_provider"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_ACTIVE_TASK_ID = "active_task_id"
        private const val KEY_AI_ENABLED     = "ai_enabled"
        private const val KEY_ACTION_DELAY   = "action_delay"
        private const val KEY_MAX_ACTIONS    = "max_actions"
        private const val KEY_FLOAT_X        = "float_x"
        private const val KEY_FLOAT_Y        = "float_y"
        private const val KEY_USE_UI_TREE    = "use_ui_tree"

        const val PROVIDER_HF     = "huggingface"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_GEMINI = "google"

        // ── نماذج HuggingFace ──────────────────────────────────────────────
        const val DEFAULT_MODEL_HF = "Qwen/Qwen2.5-VL-7B-Instruct"
        val MODELS_HF = arrayOf(
            "Qwen/Qwen2.5-VL-7B-Instruct",
            "Qwen/Qwen2.5-VL-3B-Instruct",
            "meta-llama/Llama-4-Scout-17B-16E-Instruct",
            "google/gemma-3-12b-it",
            "google/gemma-3-4b-it",
            "Qwen/Qwen3-VL-8B-Instruct",
            "Qwen/Qwen3-VL-4B-Instruct",
            "Qwen/Qwen3-VL-2B-Instruct"
        )

        // ── نماذج OpenAI ──────────────────────────────────────────────────
        const val DEFAULT_MODEL_OPENAI = "gpt-4o"
        val MODELS_OPENAI = arrayOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo"
        )

        // ── نماذج Google Gemini ───────────────────────────────────────────
        const val DEFAULT_MODEL_GEMINI = "gemini-2.0-flash"
        val MODELS_GEMINI = arrayOf(
            "gemini-2.0-flash",          // الأسرع والأذكى للتحكم بالشاشة
            "gemini-2.0-flash-lite",     // أخف وأسرع - حصة مجانية أكبر
            "gemini-1.5-flash",          // مستقر ومجرَّب
            "gemini-1.5-flash-8b",       // الأسرع - مناسب للمهام البسيطة
            "gemini-1.5-pro"             // الأذكى - لكن حصة مجانية أقل
        )

        @Volatile private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager =
            instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
    }
}
