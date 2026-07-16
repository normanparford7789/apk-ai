package com.aicontrol.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.aicontrol.app.ai.models.*
import com.aicontrol.app.services.AIAccessibilityService
import com.aicontrol.app.services.ScreenCaptureService
import com.aicontrol.app.utils.PreferencesManager
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException

class AIController(private val context: Context) {

    private val prefs = PreferencesManager.getInstance(context)
    private val apiService get() = OpenAIClient.create(prefs.apiBaseUrl)
    private val gson = Gson()
    private var isRunning = false
    private var job: Job? = null
    private var actionCount = 0
    private var lastUiTreeHash: Int = 0

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
        lastUiTreeHash = 0

        onStatusUpdate?.invoke("جاري تشغيل الذكاء الاصطناعي...")

        while (isRunning && actionCount < maxActions) {
            try {
                val uiTree = if (prefs.useUITree) {
                    AIAccessibilityService.instance?.getUITree() ?: ""
                } else ""

                val treeIsUsable = uiTree.isNotBlank() &&
                    uiTree.lines().count { it.isNotBlank() } >= 3

                if (treeIsUsable) {
                    val currentHash = uiTree.hashCode()
                    if (currentHash == lastUiTreeHash) {
                        onStatusUpdate?.invoke("⏳ انتظار تغيير الشاشة...")
                        delay(400)
                        continue
                    }
                    lastUiTreeHash = currentHash

                    onStatusUpdate?.invoke("⚡ تحليل واجهة الشاشة...")
                    val result = analyzeScreenByText(uiTree, taskDescription)

                    if (!result.success || result.action == null) {
                        onStatusUpdate?.invoke("فشل التحليل: ${result.errorMessage}")
                        delay(1000)
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

                    lastUiTreeHash = 0
                    executeAction(action)
                    actionCount++
                    delay(action.waitMs.toLong().coerceIn(300L, 3000L))

                } else {
                    onStatusUpdate?.invoke("جاري التقاط الشاشة...")
                    val screenshot = captureScreen()

                    if (screenshot == null) {
                        onStatusUpdate?.invoke("لم يتم التقاط الشاشة — تأكد من تفعيل خدمة المساعدة")
                        delay(3000)
                        continue
                    }

                    onStatusUpdate?.invoke("جاري تحليل الشاشة بالصورة...")
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
                    delay(action.waitMs.toLong().coerceIn(800L, 5000L))
                }

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

    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        AIAccessibilityService.instance?.captureScreen()
            ?: ScreenCaptureService.instance?.getLatestBitmap()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI-Tree analysis (text-only — no image, cheaper and faster)
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun analyzeScreenByText(
        uiTree: String,
        taskDescription: String
    ): AIAnalysisResult = withContext(Dispatchers.IO) {
        val apiKey = prefs.openAiApiKey
        val model  = prefs.selectedModel

        if (apiKey.isBlank()) {
            return@withContext AIAnalysisResult(
                success = false, action = null, rawResponse = "",
                errorMessage = "مفتاح API غير موجود. يرجى إضافته في الإعدادات."
            )
        }

        val messages = listOf(
            Message("system", buildSystemPromptForUITree()),
            Message("user", buildUserPromptForUITree(taskDescription, uiTree))
        )

        val request = ChatCompletionRequest(
            model       = model,
            messages    = messages,
            maxTokens   = 256,
            temperature = 0.1
        )

        var lastError: String? = null
        var attempt = 0

        while (attempt < MAX_RETRIES && isRunning) {
            try {
                val response = apiService.chatCompletion(
                    authorization = "Bearer $apiKey",
                    request       = request
                )

                if (response.isSuccessful) {
                    val content = response.body()
                        ?.choices?.firstOrNull()
                        ?.message?.content ?: ""
                    return@withContext parseAIResponse(content)
                }

                val errorBody = response.errorBody()?.string() ?: "Unknown error"

                if (response.code() == 429) {
                    // انتظر الوقت المحدد ثم أعد المحاولة — لكن بحد أقصى
                    val waitSec = parseRetryAfter(errorBody).coerceIn(5L, 30L)
                    Log.w(TAG, "Rate limit on $model (attempt ${attempt+1}/$MAX_RETRIES), waiting ${waitSec}s")
                    onStatusUpdate?.invoke("⏳ rate limit — انتظار ${waitSec} ثانية... (${attempt+1}/$MAX_RETRIES)")
                    delay(waitSec * 1000L)
                    attempt++
                    continue
                }

                if (response.code() in 400..499) {
                    val isModelError = errorBody.contains("model_not_supported", true) ||
                        errorBody.contains("not supported", true) ||
                        errorBody.contains("not found", true) ||
                        errorBody.contains("does not exist", true) ||
                        errorBody.contains("MODEL_NOT_FOUND", true)

                    if (isModelError) {
                        val modelList = prefs.currentModelList
                        val fallback = modelList.firstOrNull { it != model }
                        if (fallback != null) {
                            prefs.selectedModel = fallback
                            onStatusUpdate?.invoke("النموذج $model غير مدعوم، جاري التبديل إلى $fallback")
                        }
                    }
                    return@withContext AIAnalysisResult(
                        success = false, action = null, rawResponse = errorBody,
                        errorMessage = "خطأ API: ${response.code()} - $errorBody"
                    )
                }

                lastError = "خطأ API: ${response.code()} - $errorBody"
                attempt++

            } catch (e: IOException) {
                Log.w(TAG, "Network error attempt ${attempt + 1}", e)
                lastError = "خطأ شبكة: ${e.message}"
                attempt++
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
            }
        }

        AIAnalysisResult(
            success = false, action = null, rawResponse = "",
            errorMessage = lastError ?: "فشل الاتصال بعد $MAX_RETRIES محاولات"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Screenshot-based analysis (vision — fallback)
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun analyzeScreen(
        bitmap: Bitmap,
        taskDescription: String
    ): AIAnalysisResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)
        val apiKey = prefs.openAiApiKey
        val model  = prefs.selectedModel

        if (apiKey.isBlank()) {
            return@withContext AIAnalysisResult(
                success = false, action = null, rawResponse = "",
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
            model       = model,
            messages    = messages,
            maxTokens   = 512,
            temperature = 0.1
        )

        var lastError: String? = null
        var attempt = 0

        while (attempt < MAX_RETRIES && isRunning) {
            try {
                val response = apiService.chatCompletion(
                    authorization = "Bearer $apiKey",
                    request       = request
                )

                if (response.isSuccessful) {
                    val content = response.body()
                        ?.choices?.firstOrNull()
                        ?.message?.content ?: ""
                    return@withContext parseAIResponse(content)
                }

                val errorBody = response.errorBody()?.string() ?: "Unknown error"

                if (response.code() == 429) {
                    val waitSec = parseRetryAfter(errorBody).coerceIn(5L, 30L)
                    Log.w(TAG, "Rate limit on $model (attempt ${attempt+1}/$MAX_RETRIES), waiting ${waitSec}s")
                    onStatusUpdate?.invoke("⏳ rate limit — انتظار ${waitSec} ثانية... (${attempt+1}/$MAX_RETRIES)")
                    delay(waitSec * 1000L)
                    attempt++
                    continue
                }

                if (response.code() in 400..499) {
                    return@withContext AIAnalysisResult(
                        success = false, action = null, rawResponse = errorBody,
                        errorMessage = "خطأ API: ${response.code()} - $errorBody"
                    )
                }

                lastError = "خطأ API: ${response.code()} - $errorBody"
                attempt++

            } catch (e: IOException) {
                lastError = "خطأ شبكة: ${e.message}"
                attempt++
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
            }
        }

        AIAnalysisResult(
            success = false, action = null, rawResponse = "",
            errorMessage = lastError ?: "فشل الاتصال"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Prompts
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildSystemPromptForUITree(): String = """
You are an Android UI controller. You receive a list of screen elements and must choose the next action.
Reply with JSON only — no markdown, no explanation:
{"action":"tap","x":200,"y":500,"reason":"short reason","completed":false}
Available actions: tap / long_press / type (add "text" field) / scroll (add "direction": up|down|left|right) / back / home / complete
Use exact x,y coordinates from the elements list. Set completed:true when the task is fully done.
    """.trimIndent()

    private fun buildUserPromptForUITree(taskDescription: String, uiTree: String): String =
        "Task: $taskDescription\n\nScreen elements:\n$uiTree\n\nNext action JSON:"

    private fun buildSystemPrompt(): String = """
أنت مساعد ذكاء اصطناعي متخصص في التحكم بشاشة الهاتف Android.
تقوم بتحليل لقطات الشاشة وتقرر الإجراء التالي لإكمال المهمة.
أعطِ دائماً إجابة بتنسيق JSON صحيح فقط، بدون أي نص إضافي.
تنسيق الإجابة:
{"action":"tap|long_press|type|swipe|scroll|back|home|wait|complete|clear_text","x":540,"y":960,"text":"للكتابة","direction":"up|down|left|right","reason":"وصف قصير","completed":false,"wait_ms":1000}
    """.trimIndent()

    private fun buildUserPrompt(taskDescription: String): String =
        "المهمة: $taskDescription\n\nانظر إلى لقطة الشاشة وقرر الإجراء التالي. أعطِ JSON فقط."

    // ═══════════════════════════════════════════════════════════════════════
    // JSON parsing
    // ═══════════════════════════════════════════════════════════════════════

    private fun parseAIResponse(content: String): AIAnalysisResult {
        return try {
            val jsonStr = extractJson(content)
            val action = gson.fromJson(jsonStr, AIAction::class.java)
            AIAnalysisResult(success = true, action = action, rawResponse = content)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing AI response: $content", e)
            AIAnalysisResult(
                success = false, action = null, rawResponse = content,
                errorMessage = "فشل تحليل الاستجابة: ${e.message}"
            )
        }
    }

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

    // ═══════════════════════════════════════════════════════════════════════
    // Action execution
    // ═══════════════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private fun getScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private suspend fun executeAction(action: AIAction) {
        val service = AIAccessibilityService.instance ?: return
        val (screenW, screenH) = getScreenSize()

        fun Double.toPixelX(): Float =
            if (this in 0.0..1.0) (this * screenW).toFloat() else this.toFloat()
        fun Double.toPixelY(): Float =
            if (this in 0.0..1.0) (this * screenH).toFloat() else this.toFloat()

        when (action.action) {
            AIAction.ACTION_TAP        -> {
                val x = action.x?.toPixelX() ?: return
                val y = action.y?.toPixelY() ?: return
                service.performTap(x, y)
            }
            AIAction.ACTION_LONG_PRESS -> {
                val x = action.x?.toPixelX() ?: return
                val y = action.y?.toPixelY() ?: return
                service.performLongPress(x, y)
            }
            AIAction.ACTION_TYPE       -> action.text?.let { service.typeText(it) }
            AIAction.ACTION_SWIPE      -> {
                val x = action.x?.toPixelX() ?: return
                val y = action.y?.toPixelY() ?: return
                service.performSwipe(x, y, action.direction ?: "up")
            }
            AIAction.ACTION_SCROLL     -> service.performScroll(action.direction ?: "down")
            AIAction.ACTION_BACK       ->
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            AIAction.ACTION_HOME       ->
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            AIAction.ACTION_RECENTS    ->
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            AIAction.ACTION_WAIT       -> delay(action.waitMs.toLong())
            AIAction.ACTION_CLEAR_TEXT -> service.clearText()
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        val maxSide = 768
        val scaled = if (bitmap.width > maxSide || bitmap.height > maxSide) {
            val ratio = minOf(maxSide.toFloat() / bitmap.width, maxSide.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else bitmap
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseRetryAfter(errorBody: String): Long =
        Regex("""retry[_ ]?after["\s:]*(\d+)""", RegexOption.IGNORE_CASE)
            .find(errorBody)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""(\d+)\s*s""", RegexOption.IGNORE_CASE)
                .find(errorBody)?.groupValues?.get(1)?.toLongOrNull()
            ?: 10L

    companion object {
        private const val TAG = "AIController"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1500L
    }
}
