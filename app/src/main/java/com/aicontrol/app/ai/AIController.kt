package com.aicontrol.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.aicontrol.app.ai.models.*
import com.aicontrol.app.services.AIAccessibilityService
import com.aicontrol.app.services.ScreenCaptureService
import com.aicontrol.app.utils.PreferencesManager
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class AIController(private val context: Context) {

    private val prefs = PreferencesManager.getInstance(context)
    private val apiService get() = OpenAIClient.create(prefs.apiBaseUrl)
    private val gson = Gson()
    private var isRunning = false
    private var job: Job? = null
    private var actionCount = 0

    var onStatusUpdate: ((String) -> Unit)? = null
    var onActionExecuted: ((AIAction) -> Unit)? = null
    var onCompleted: ((Boolean, String) -> Unit)? = null

    val isActive: Boolean get() = isRunning

    fun start(taskDescription: String) {
        if (isRunning) return
        isRunning = true
        actionCount = 0
        job = CoroutineScope(Dispatchers.IO).launch {
            runAILoop(taskDescription)
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
        onStatusUpdate?.invoke("تم إيقاف الذكاء الاصطناعي")
    }

    private suspend fun runAILoop(taskDescription: String) {
        val maxActions = prefs.maxActions
        val actionDelay = prefs.actionDelay.toLong()

        onStatusUpdate?.invoke("جاري تشغيل الذكاء الاصطناعي...")

        while (isRunning && actionCount < maxActions) {
            try {
                onStatusUpdate?.invoke("جاري التقاط الشاشة...")
                val screenshot = captureScreen()

                if (screenshot == null) {
                    onStatusUpdate?.invoke("لم يتم التقاط الشاشة — تأكد من تفعيل خدمة المساعدة")
                    delay(3000)
                    continue
                }

                onStatusUpdate?.invoke("جاري تحليل الشاشة بالذكاء الاصطناعي...")
                val result = analyzeScreen(screenshot, taskDescription)

                if (!result.success || result.action == null) {
                    onStatusUpdate?.invoke("فشل التحليل: ${result.errorMessage}")
                    delay(2000)
                    continue
                }

                val action = result.action
                onStatusUpdate?.invoke("الإجراء: ${action.reason}")
                onActionExecuted?.invoke(action)

                if (action.completed || action.action == AIAction.ACTION_COMPLETE) {
                    withContext(Dispatchers.Main) {
                        onCompleted?.invoke(true, "تم إكمال المهمة بنجاح!")
                    }
                    isRunning = false
                    break
                }

                executeAction(action)
                actionCount++

                delay(action.waitMs.toLong().coerceAtLeast(actionDelay))

            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in AI loop", e)
                onStatusUpdate?.invoke("خطأ: ${e.message}")
                delay(3000)
            }
        }

        if (actionCount >= maxActions) {
            withContext(Dispatchers.Main) {
                onCompleted?.invoke(false, "تم الوصول للحد الأقصى من الإجراءات ($maxActions)")
            }
        }
        isRunning = false
    }

    private suspend fun captureScreen(): Bitmap? {
        return withContext(Dispatchers.IO) {
            // Try ScreenCaptureService first (MediaProjection)
            ScreenCaptureService.instance?.getLatestBitmap()
                ?: AIAccessibilityService.instance?.captureScreen()
        }
    }

    private suspend fun analyzeScreen(bitmap: Bitmap, taskDescription: String): AIAnalysisResult {
        return withContext(Dispatchers.IO) {
            try {
                val base64Image = bitmapToBase64(bitmap)
                val apiKey = prefs.openAiApiKey
                val model = prefs.selectedModel

                if (apiKey.isBlank()) {
                    return@withContext AIAnalysisResult(
                        success = false,
                        action = null,
                        rawResponse = "",
                        errorMessage = "مفتاح API غير موجود. يرجى إضافته في الإعدادات."
                    )
                }

                val messages = listOf(
                    Message("system", buildSystemPrompt()),
                    Message("user", listOf(
                        TextContent(text = buildUserPrompt(taskDescription)),
                        ImageContent(imageUrl = ImageUrl(url = "data:image/jpeg;base64,$base64Image"))
                    ))
                )

                val request = ChatCompletionRequest(
                    model = model,
                    messages = messages,
                    maxTokens = 512,
                    temperature = 0.1
                )

                val response = apiService.chatCompletion(
                    authorization = "Bearer $apiKey",
                    request = request
                )

                if (response.isSuccessful) {
                    val content = response.body()?.choices?.firstOrNull()?.message?.content ?: ""
                    parseAIResponse(content)
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    AIAnalysisResult(
                        success = false,
                        action = null,
                        rawResponse = errorBody,
                        errorMessage = "خطأ API: ${response.code()} - $errorBody"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing screen", e)
                AIAnalysisResult(success = false, action = null, rawResponse = "", errorMessage = e.message)
            }
        }
    }

    private fun buildSystemPrompt(): String = """
أنت مساعد ذكاء اصطناعي متخصص في التحكم بشاشة الهاتف Android.
تقوم بتحليل لقطات الشاشة وتقرر الإجراء التالي لإكمال المهمة.

قواعد مهمة:
1. أعطِ دائماً إجابة بتنسيق JSON صحيح فقط، بدون أي نص إضافي
2. حدد الإحداثيات بدقة بناءً على العناصر المرئية في الشاشة
3. إذا اكتملت المهمة، اضبط "completed" على true

تنسيق الإجابة (JSON فقط):
{
  "action": "tap|long_press|type|swipe|scroll|back|home|wait|complete|clear_text",
  "x": 540,
  "y": 960,
  "text": "النص للكتابة (للإجراء type فقط)",
  "direction": "up|down|left|right (للتمرير فقط)",
  "reason": "وصف قصير للإجراء",
  "completed": false,
  "wait_ms": 1000
}
    """.trimIndent()

    private fun buildUserPrompt(taskDescription: String): String = """
المهمة المطلوبة: $taskDescription

انظر إلى لقطة الشاشة الحالية وقرر الإجراء التالي لإتمام المهمة.
أعطِ إجابة JSON فقط.
    """.trimIndent()

    private fun parseAIResponse(content: String): AIAnalysisResult {
        return try {
            val jsonStr = extractJson(content)
            val action = gson.fromJson(jsonStr, AIAction::class.java)
            AIAnalysisResult(success = true, action = action, rawResponse = content)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing AI response: $content", e)
            AIAnalysisResult(
                success = false,
                action = null,
                rawResponse = content,
                errorMessage = "فشل تحليل الاستجابة: ${e.message}"
            )
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) text.substring(start, end + 1) else text
    }

    private suspend fun executeAction(action: AIAction) {
        val service = AIAccessibilityService.instance ?: return
        when (action.action) {
            AIAction.ACTION_TAP -> {
                val x = action.x?.toFloat() ?: return
                val y = action.y?.toFloat() ?: return
                service.performTap(x, y)
            }
            AIAction.ACTION_LONG_PRESS -> {
                val x = action.x?.toFloat() ?: return
                val y = action.y?.toFloat() ?: return
                service.performLongPress(x, y)
            }
            AIAction.ACTION_TYPE -> action.text?.let { service.typeText(it) }
            AIAction.ACTION_SWIPE -> {
                val x = action.x?.toFloat() ?: return
                val y = action.y?.toFloat() ?: return
                service.performSwipe(x, y, action.direction ?: "up")
            }
            AIAction.ACTION_SCROLL -> service.performScroll(action.direction ?: "down")
            AIAction.ACTION_BACK ->
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            AIAction.ACTION_HOME ->
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            AIAction.ACTION_RECENTS ->
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            AIAction.ACTION_WAIT -> delay(action.waitMs.toLong())
            AIAction.ACTION_CLEAR_TEXT -> service.clearText()
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        val maxSide = 1280
        val scaled = if (bitmap.width > maxSide || bitmap.height > maxSide) {
            val ratio = minOf(maxSide.toFloat() / bitmap.width, maxSide.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else bitmap
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "AIController"
    }
}

// Needed import for GLOBAL_ACTION_BACK etc.
private typealias AccessibilityService = android.accessibilityservice.AccessibilityService
