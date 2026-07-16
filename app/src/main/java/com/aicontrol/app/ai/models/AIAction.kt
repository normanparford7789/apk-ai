package com.aicontrol.app.ai.models

import com.google.gson.annotations.SerializedName

data class AIAction(
    @SerializedName("action") val action: String,
    // Double? بدلاً من Int? لأن بعض النماذج ترجع إحداثيات عشرية أو مُعيّرة (0.0–1.0)
    @SerializedName("x") val x: Double? = null,
    @SerializedName("y") val y: Double? = null,
    @SerializedName("text") val text: String? = null,
    @SerializedName("direction") val direction: String? = null,
    @SerializedName("reason") val reason: String = "",
    @SerializedName("completed") val completed: Boolean = false,
    @SerializedName("wait_ms") val waitMs: Int = 1000
) {
    companion object {
        const val ACTION_TAP = "tap"
        const val ACTION_LONG_PRESS = "long_press"
        const val ACTION_TYPE = "type"
        const val ACTION_SWIPE = "swipe"
        const val ACTION_SCROLL = "scroll"
        const val ACTION_BACK = "back"
        const val ACTION_HOME = "home"
        const val ACTION_RECENTS = "recents"
        const val ACTION_WAIT = "wait"
        const val ACTION_COMPLETE = "complete"
        const val ACTION_OPEN_APP = "open_app"
        const val ACTION_CLEAR_TEXT = "clear_text"
    }
}

data class AIAnalysisResult(
    val success: Boolean,
    val action: AIAction?,
    val rawResponse: String,
    val errorMessage: String? = null
)
