package com.aicontrol.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_OPENAI_API_KEY, value) }

    var selectedModel: String
        get() = prefs.getString(KEY_SELECTED_MODEL, "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit { putString(KEY_SELECTED_MODEL, value) }

    var activeTaskId: Long
        get() = prefs.getLong(KEY_ACTIVE_TASK_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_ACTIVE_TASK_ID, value) }

    var isAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_AI_ENABLED, value) }

    var actionDelay: Int
        get() = prefs.getInt(KEY_ACTION_DELAY, 2000)
        set(value) = prefs.edit { putInt(KEY_ACTION_DELAY, value) }

    var maxActions: Int
        get() = prefs.getInt(KEY_MAX_ACTIONS, 50)
        set(value) = prefs.edit { putInt(KEY_MAX_ACTIONS, value) }

    var floatX: Int
        get() = prefs.getInt(KEY_FLOAT_X, 100)
        set(value) = prefs.edit { putInt(KEY_FLOAT_X, value) }

    var floatY: Int
        get() = prefs.getInt(KEY_FLOAT_Y, 300)
        set(value) = prefs.edit { putInt(KEY_FLOAT_Y, value) }

    companion object {
        private const val PREFS_NAME = "ai_control_prefs"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_ACTIVE_TASK_ID = "active_task_id"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_ACTION_DELAY = "action_delay"
        private const val KEY_MAX_ACTIONS = "max_actions"
        private const val KEY_FLOAT_X = "float_x"
        private const val KEY_FLOAT_Y = "float_y"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
