package com.aicontrol.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.aicontrol.app.ai.OpenAIClient

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── API ──────────────────────────────────────────────────────────────────

    /** مفتاح الـ API (يبدأ بـ hf_ لـ HuggingFace أو sk- لـ OpenAI) */
    var openAiApiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    /** مزود الخدمة: "huggingface" أو "openai" */
    var apiProvider: String
        get() = prefs.getString(KEY_API_PROVIDER, PROVIDER_HF) ?: PROVIDER_HF
        set(value) = prefs.edit { putString(KEY_API_PROVIDER, value) }

    /** الـ base URL المستخدمة فعلياً حسب المزود */
    val apiBaseUrl: String
        get() = if (apiProvider == PROVIDER_OPENAI)
            OpenAIClient.BASE_URL_OPENAI
        else
            OpenAIClient.BASE_URL_HUGGINGFACE

    /** النموذج المختار */
    var selectedModel: String
        get() = prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL_HF) ?: DEFAULT_MODEL_HF
        set(value) = prefs.edit { putString(KEY_SELECTED_MODEL, value) }

    val defaultModel: String
        get() = DEFAULT_MODEL_HF

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

        const val PROVIDER_HF     = "huggingface"
        const val PROVIDER_OPENAI = "openai"

        // النموذج الافتراضي: Qwen2.5-VL-7B مدعوم بشكل موثوق على HuggingFace Router
        const val DEFAULT_MODEL_HF = "Qwen/Qwen2.5-VL-7B-Instruct"

        val MODELS_HF = arrayOf(
            "Qwen/Qwen2.5-VL-7B-Instruct",      // مستقر ومدعوم - الافتراضي
            "Qwen/Qwen2.5-VL-3B-Instruct",      // أخف وأسرع
            "meta-llama/Llama-4-Scout-17B-16E-Instruct",
            "google/gemma-3-12b-it",
            "google/gemma-3-4b-it",
            "Qwen/Qwen3-VL-8B-Instruct",        // قد لا يكون متاحاً على بعض المزودين
            "Qwen/Qwen3-VL-4B-Instruct",
            "Qwen/Qwen3-VL-2B-Instruct"
        )
        val MODELS_OPENAI = arrayOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo"
        )

        @Volatile private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager =
            instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
    }
}
