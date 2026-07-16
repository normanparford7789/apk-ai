package com.aicontrol.app.ai

import android.content.Context
import android.util.Log
import com.aicontrol.app.ai.models.ChatCompletionRequest
import com.aicontrol.app.ai.models.Message
import com.aicontrol.app.ai.models.TextContent
import com.aicontrol.app.services.AIAccessibilityService
import com.aicontrol.app.utils.PreferencesManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatController(private val context: Context) {

    private val prefs = PreferencesManager.getInstance(context)
    private val apiService get() = OpenAIClient.create(prefs.apiBaseUrl)
    private val gson = Gson()

    data class ChatMessage(val role: String, val content: String)

    private val conversation = mutableListOf<ChatMessage>()

    var onChatResponse: ((String) -> Unit)? = null
    var onChatError: ((String) -> Unit)? = null

    private fun buildSystemPrompt(): String = """
You are an AI Android assistant. You help the user by analyzing screen elements (UI Tree) and answering questions.
You can see the current screen elements in text form. Answer the user's question or instruct them on what to do.
Be concise and clear. Answer in the same language as the user (Arabic or English).
If the user asks you to perform an action, explain what action they should take based on the visible elements.
    """.trimIndent()

    suspend fun sendMessage(userMessage: String, withScreen: Boolean = true): String =
        withContext(Dispatchers.IO) {
            conversation.add(ChatMessage("user", userMessage))

            val screenContext = if (withScreen) {
                val tree = AIAccessibilityService.instance?.getUITree() ?: ""
                if (tree.isNotBlank()) "\n\nCurrent screen elements:\n$tree\n" else ""
            } else ""

            val systemMsg = Message("system", buildSystemPrompt())
            val historyMsgs = conversation.takeLast(MAX_HISTORY).map {
                Message(it.role, listOf(TextContent(text = it.content)))
            }
            val userMsg = Message("user", listOf(TextContent(text = userMessage + screenContext)))

            val messages = listOf(systemMsg) + historyMsgs + listOf(userMsg)

            val apiKey = prefs.openAiApiKey
            if (apiKey.isBlank()) {
                val err = "مفتاح API غير موجود. يرجى إضافته في الإعدادات."
                onChatError?.invoke(err)
                return@withContext err
            }

            val request = ChatCompletionRequest(
                model       = prefs.selectedModel,
                messages    = messages,
                maxTokens   = 512,
                temperature = 0.3
            )

            try {
                val response = apiService.chatCompletion(
                    authorization = "Bearer $apiKey",
                    request       = request
                )

                if (response.isSuccessful) {
                    val content = response.body()
                        ?.choices?.firstOrNull()
                        ?.message?.content ?: "لم أستطع توليد رد."
                    conversation.add(ChatMessage("assistant", content))
                    onChatResponse?.invoke(content)
                    return@withContext content
                }

                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                val err = if (response.code() == 429) {
                    "تجاوزت الحد المسموح. انتظر قليلاً ثم حاول مرة أخرى."
                } else {
                    "خطأ API: ${response.code()} - ${errorBody.take(200)}"
                }
                onChatError?.invoke(err)
                err
            } catch (e: Exception) {
                Log.e(TAG, "Chat error", e)
                val err = "خطأ: ${e.message}"
                onChatError?.invoke(err)
                err
            }
        }

    fun clearHistory() {
        conversation.clear()
    }

    companion object {
        private const val TAG = "ChatController"
        private const val MAX_HISTORY = 10
    }
}
