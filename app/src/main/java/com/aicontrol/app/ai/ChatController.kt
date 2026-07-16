package com.aicontrol.app.ai

import android.content.Context
import android.util.Log
import com.aicontrol.app.ai.models.ChatCompletionRequest
import com.aicontrol.app.ai.models.Message
import com.aicontrol.app.ai.models.TextContent
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

    private fun buildTrainingSystemPrompt(): String = """
أنت مساعد ذكي لتدريب تطبيق أندرويد على تنفيذ المهام.
المستخدم يصف لك بالعربية ما يريد من التطبيق أن يفعله بشكل تلقائي.
تحاور المستخدم بشكل طبيعي وواضح، واسأله عن التفاصيل الناقصة.
لا تكتب أوامر JSON — هذه محادثة عادية لفهم المهمة.
اجعل ردودك قصيرة وواضحة.
    """.trimIndent()

    private fun buildExtractionPrompt(): String = """
بناءً على المحادثة التالية، استخرج المهمة النهائية التي يريد المستخدم تنفيذها.
أعطِ النتيجة بتنسيق JSON فقط:
{"title":"عنوان قصير","description":"وصف تفصيلي للخطوات"}
الوصف يجب أن يكون تعليمات واضحة للذكاء الاصطناعي لتنفيذها على الشاشة.
    """.trimIndent()

    suspend fun sendTrainingMessage(userMessage: String): String =
        withContext(Dispatchers.IO) {
            conversation.add(ChatMessage("user", userMessage))

            val systemMsg = Message("system", buildTrainingSystemPrompt())
            val historyMsgs = conversation.takeLast(MAX_HISTORY).map {
                Message(it.role, listOf(TextContent(text = it.content)))
            }

            val messages = listOf(systemMsg) + historyMsgs

            val apiKey = prefs.openAiApiKey
            if (apiKey.isBlank()) {
                val err = "مفتاح API غير موجود. يرجى إضافته في الإعدادات."
                onChatError?.invoke(err)
                return@withContext err
            }

            val request = ChatCompletionRequest(
                model       = prefs.selectedModel,
                messages    = messages,
                maxTokens   = 400,
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

    data class ExtractedTask(val title: String, val description: String)

    suspend fun extractTaskFromConversation(): ExtractedTask? =
        withContext(Dispatchers.IO) {
            if (conversation.isEmpty()) return@withContext null

            val apiKey = prefs.openAiApiKey
            if (apiKey.isBlank()) return@withContext null

            val systemMsg = Message("system", buildExtractionPrompt())
            val convoText = conversation.joinToString("\n") {
                "${if (it.role == "user") "المستخدم" else "المساعد"}: ${it.content}"
            }
            val userMsg = Message("user", listOf(TextContent(text = convoText)))

            val request = ChatCompletionRequest(
                model       = prefs.selectedModel,
                messages    = listOf(systemMsg, userMsg),
                maxTokens   = 300,
                temperature = 0.1
            )

            try {
                val response = apiService.chatCompletion(
                    authorization = "Bearer $apiKey",
                    request       = request
                )

                if (response.isSuccessful) {
                    val content = response.body()
                        ?.choices?.firstOrNull()
                        ?.message?.content ?: return@withContext null

                    val jsonStr = extractJson(content)
                    val parsed = gson.fromJson(jsonStr, TaskJson::class.java)
                    if (parsed != null && parsed.title != null && parsed.description != null) {
                        return@withContext ExtractedTask(parsed.title, parsed.description)
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Extract error", e)
                null
            }
        }

    private data class TaskJson(val title: String?, val description: String?)

    private fun extractJson(text: String): String {
        val cleaned = text
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*"), "")
            .trim()
        val start = cleaned.indexOf('{')
        val end   = cleaned.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) cleaned.substring(start, end + 1)
        else cleaned
    }

    fun clearHistory() {
        conversation.clear()
    }

    companion object {
        private const val TAG = "ChatController"
        private const val MAX_HISTORY = 10
    }
}
