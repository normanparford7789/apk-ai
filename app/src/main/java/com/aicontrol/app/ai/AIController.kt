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
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class AIController(private val context: Context) {

    private val prefs = PreferencesManager.getInstance(context)
    private val apiService = OpenAIClient.create()
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
                // Take screenshot
                onStatusUpdate?.invoke("جاري التقاط الشاشة...")
                val screenshot = captureScreen()

                if (screenshot == null) {
                    onStatusUpdate?.invoke("فشل التقاط الشاشة، إعادة المحاولة...")
                    delay(2000)
                    continue
                }

                // Analyze with AI
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

                // Check if task is complete
                if (action.completed || action.action == AIAction.ACTION_COMPLETE) {
                    withContext(Dispatchers.Main) {
                        onCompleted?.invoke(true, "تم إكمال المهمة بنجاح!")
                    }
                    isRunning = false
                    break
                }

                // Execute action
                executeAction(action)
                actionCount++

                // Wait between actions
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
        return withContext(Dispatchers.Main) {
            AIAccessibilityService.instance?.captureScreen()
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

                val systemPrompt = buildSystemPrompt()
                val userPrompt = buildUserPrompt(taskDescription)

                val messages = listOf(
                    Message("system", systemPrompt),
                    Message("user", listOf(
                        TextContent(text = userPrompt),
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
                    val body = response.body()
                    val content = body?.choices?.firstOrNull()?.message?.content ?: ""
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
                AIAnalysisResult(
                    success = false,
                    action = null,
                    rawResponse = "",
                    errorMessage = e.message
                )
            }
        }
    }

    private fun buildSystemPrompt(): String {
        return """
أنت مساعد ذكاء اصطناعي متخصص في التحكم بشاشة الهاتف Android.
تقوم بتحليل لقطات الشاشة وتقرر الإجراء التالي لإكمال المهمة.

قواعد مهمة:
1. أعطِ دائماً إجابة بتنسيق JSON صحيح فقط، بدون أي نص إضافي
2. حدد الإحداثيات بدقة بناءً على العناصر المرئية في الشاشة
3. إذا اكتملت المهمة، اضبط "completed" على true
4. كن دقيقاً في تحديد العناصر التي تريد الضغط عليها

تنسيق الإجابة المطلوب (JSON فقط):
{
  "action": "tap|long_press|type|swipe|scroll|back|home|wait|complete",
  "x": 540,
  "y": 960,
  "text": "النص للكتابة (للإجراء type فقط)",
  "direction": "up|down|left|right (للتمرير فقط)",
  "reason": "وصف قصير للإجراء",
  "completed": false,
  "wait_ms": 1000
}

أنواع الإجراءات:
- tap: الضغط على إحداثيات محددة
- long_press: الضغط المطول
- type: كتابة نص
- swipe: التمرير
- scroll: التمرير في اتجاه
- back: زر الرجوع
- home: الشاشة الرئيسية
- wait: الانتظار
- complete: المهمة اكتملت
        """.trimIndent()
    }

    private fun buildUserPrompt(taskDescription: String): String {
        return """
المهمة المطلوبة: $taskDescription

انظر إلى لقطة الشاشة الحالية وقرر الإجراء التالي لإتمام المهمة.
أعطِ إجابة JSON فقط.
        """.trimIndent()
    }

    private fun parseAIResponse(content: String): AIAnalysisResult {
        return try {
            // Extract JSON from response
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
        return if (start != -1 && end != -1) text.substring(start, end + 1) else text
    }

    private suspend fun executeAction(action: AIAction) {
        val service = AIAccessibilityService.instance ?: return

        when (action.action) {
            AIAction.ACTION_TAP -> {
                val x = action.x ?: return
                val y = action.y ?: return
                service.performTap(x.toFloat(), y.toFloat())
            }
            AIAction.ACTION_LONG_PRESS -> {
                val x = action.x ?: return
                val y = action.y ?: return
                service.performLongPress(x.toFloat(), y.toFloat())
            }
            AIAction.ACTION_TYPE -> {
                action.text?.let { service.typeText(it) }
            }
            AIAction.ACTION_SWIPE -> {
                val x = action.x ?: return
                val y = action.y ?: return
                val direction = action.direction ?: "up"
                service.performSwipe(x.toFloat(), y.toFloat(), direction)
            }
            AIAction.ACTION_SCROLL -> {
                service.performScroll(action.direction ?: "down")
            }
            AIAction.ACTION_BACK -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
            AIAction.ACTION_HOME -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            }
            AIAction.ACTION_RECENTS -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            }
            AIAction.ACTION_WAIT -> {
                delay(action.waitMs.toLong())
            }
            AIAction.ACTION_CLEAR_TEXT -> {
                service.clearText()
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Resize to reduce size
        val maxSize = 1280
        val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val ratio = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else bitmap
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "AIController"
    }
}
