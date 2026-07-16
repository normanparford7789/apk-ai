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
                // ── الطريقة الأولى: UI Tree (خفيفة، توفّر 99% من التوكنز) ────
                val uiTree = if (prefs.useUITree) {
                    AIAccessibilityService.instance?.getUITree() ?: ""
                } else ""

                val treeIsUsable = uiTree.isNotBlank() &&
                    uiTree.lines().count { it.isNotBlank() } >= 3

                val result = if (treeIsUsable) {
                    onStatusUpdate?.invoke("⚡ تحليل واجهة الشاشة (UI Tree — بدون صورة)...")
                    analyzeScreenByText(uiTree, taskDescription)
                } else {
                    // ── الطريقة الثانية: لقطة شاشة (fallback) ─────────────
                    onStatusUpdate?.invoke("جاري التقاط الشاشة...")
                    val screenshot = captureScreen()

                    if (screenshot == null) {
                        onStatusUpdate?.invoke("لم يتم التقاط الشاشة — تأكد من تفعيل خدمة المساعدة")
                        delay(3000)
                        continue
                    }

                    onStatusUpdate?.invoke("جاري تحليل الشاشة بالصورة...")
                    analyzeScreen(screenshot, taskDescription)
                }

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
            // Prefer the accessibility service's built-in takeScreenshot() (API 30+),
            // fall back to MediaProjection via ScreenCaptureService.
            AIAccessibilityService.instance?.captureScreen()
                ?: ScreenCaptureService.instance?.getLatestBitmap()
        }
    }

    private suspend fun analyzeScreen(
        bitmap: Bitmap,
        taskDescription: String,
        triedModels: Set<String> = emptySet()
    ): AIAnalysisResult {
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

                var lastError: String? = null
                repeat(MAX_RETRIES) { attempt ->
                    try {
                        val response = apiService.chatCompletion(
                            authorization = "Bearer $apiKey",
                            request = request
                        )

                        if (response.isSuccessful) {
                            val content = response.body()
                                ?.choices?.firstOrNull()
                                ?.message?.content ?: ""
                            return@withContext parseAIResponse(content)
                        } else {
                            val errorBody = response.errorBody()?.string() ?: "Unknown error"

                            // 429 = تجاوز الحصة → جرّب النموذج التالي فوراً
                            if (response.code() == 429) {
                                val modelList = prefs.currentModelList
                                val newTried = triedModels + model
                                val fallback = modelList.firstOrNull { it !in newTried }

                                if (fallback != null) {
                                    Log.w(TAG, "Quota exceeded for $model (429), switching to $fallback")
                                    onStatusUpdate?.invoke("تجاوزت حصة $model، جاري التبديل إلى $fallback...")
                                    prefs.selectedModel = fallback
                                    return@withContext analyzeScreen(bitmap, taskDescription, newTried)
                                }

                                return@withContext AIAnalysisResult(
                                    success = false,
                                    action = null,
                                    rawResponse = errorBody,
                                    errorMessage = "تجاوزت الحصة المجانية لجميع النماذج. انتظر قليلاً أو فعّل الفوترة في Google Cloud."
                                )
                            }

                            // أخطاء 4xx الأخرى لا فائدة من إعادة المحاولة
                            if (response.code() in 400..499) {
                                val isModelError = errorBody.contains("model_not_supported") ||
                                    errorBody.contains("not supported") ||
                                    errorBody.contains("not found") ||
                                    errorBody.contains("does not exist") ||
                                    errorBody.contains("MODEL_NOT_FOUND")

                                if (isModelError) {
                                    val modelList = prefs.currentModelList
                                    val newTried = triedModels + model
                                    val fallback = modelList.firstOrNull { it !in newTried }

                                    if (fallback != null) {
                                        Log.w(TAG, "Model $model not supported, trying $fallback")
                                        prefs.selectedModel = fallback
                                        return@withContext analyzeScreen(bitmap, taskDescription, newTried)
                                    }

                                    return@withContext AIAnalysisResult(
                                        success = false,
                                        action = null,
                                        rawResponse = errorBody,
                                        errorMessage = "جميع النماذج المتاحة غير مدعومة. تحقق من مفتاح API."
                                    )
                                }

                                return@withContext AIAnalysisResult(
                                    success = false,
                                    action = null,
                                    rawResponse = errorBody,
                                    errorMessage = "خطأ API: ${response.code()} - $errorBody"
                                )
                            }

                            lastError = "خطأ API: ${response.code()} - $errorBody"
                        }
                    } catch (e: IOException) {
                        Log.w(TAG, "Network error on attempt ${attempt + 1}", e)
                        lastError = "خطأ شبكة: ${e.message}"
                    }
                    if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS)
                }

                AIAnalysisResult(
                    success = false,
                    action = null,
                    rawResponse = "",
                    errorMessage = lastError ?: "فشل الاتصال بعد عدة محاولات"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing screen", e)
                AIAnalysisResult(success = false, action = null, rawResponse = "", errorMessage = e.message)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI-Tree analysis (text-only — no image, ~99% cheaper)
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun analyzeScreenByText(
        uiTree: String,
        taskDescription: String,
        triedModels: Set<String> = emptySet()
    ): AIAnalysisResult {
        return withContext(Dispatchers.IO) {
            try {
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
                    Message("user",   buildUserPromptForUITree(taskDescription, uiTree))
                )

                val request = ChatCompletionRequest(
                    model       = model,
                    messages    = messages,
                    maxTokens   = 256,   // أقل بكثير من وضع الصورة
                    temperature = 0.1
                )

                var lastError: String? = null
                repeat(MAX_RETRIES) { attempt ->
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

                        // 429 → جرّب النموذج التالي فوراً
                        if (response.code() == 429) {
                            val newTried = triedModels + model
                            val fallback = prefs.currentModelList.firstOrNull { it !in newTried }
                            if (fallback != null) {
                                Log.w(TAG, "UI-tree 429 on $model, switching to $fallback")
                                onStatusUpdate?.invoke("تجاوزت حصة $model، جاري التبديل إلى $fallback...")
                                prefs.selectedModel = fallback
                                return@withContext analyzeScreenByText(uiTree, taskDescription, newTried)
                            }
                            return@withContext AIAnalysisResult(
                                success = false, action = null, rawResponse = errorBody,
                                errorMessage = "تجاوزت الحصة المجانية لجميع النماذج. انتظر قليلاً أو فعّل الفوترة."
                            )
                        }

                        // أخطاء 4xx أخرى
                        if (response.code() in 400..499) {
                            val isModelError = errorBody.contains("model_not_supported") ||
                                errorBody.contains("not supported") ||
                                errorBody.contains("not found") ||
                                errorBody.contains("does not exist") ||
                                errorBody.contains("MODEL_NOT_FOUND")
                            if (isModelError) {
                                val newTried = triedModels + model
                                val fallback = prefs.currentModelList.firstOrNull { it !in newTried }
                                if (fallback != null) {
                                    prefs.selectedModel = fallback
                                    return@withContext analyzeScreenByText(uiTree, taskDescription, newTried)
                                }
                                return@withContext AIAnalysisResult(
                                    success = false, action = null, rawResponse = errorBody,
                                    errorMessage = "جميع النماذج غير مدعومة. تحقق من مفتاح API."
                                )
                            }
                            return@withContext AIAnalysisResult(
                                success = false, action = null, rawResponse = errorBody,
                                errorMessage = "خطأ API: ${response.code()} - $errorBody"
                            )
                        }

                        lastError = "خطأ API: ${response.code()} - $errorBody"

                    } catch (e: IOException) {
                        Log.w(TAG, "UI-tree network error attempt ${attempt + 1}", e)
                        lastError = "خطأ شبكة: ${e.message}"
                    }
                    if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS)
                }

                AIAnalysisResult(
                    success = false, action = null, rawResponse = "",
                    errorMessage = lastError ?: "فشل الاتصال بعد عدة محاولات"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in analyzeScreenByText", e)
                AIAnalysisResult(success = false, action = null, rawResponse = "", errorMessage = e.message)
            }
        }
    }

    private fun buildSystemPromptForUITree(): String = """
أنت مساعد ذكاء اصطناعي متخصص في التحكم بشاشة الهاتف Android.
ستحصل على قائمة نصية بعناصر الواجهة الموجودة على الشاشة مع إحداثياتها الدقيقة.

صيغة كل عنصر: النوع: "النص" [x=المحور_الأفقي, y=المحور_الرأسي] {الخصائص}

قرر الإجراء التالي لإكمال المهمة وأجب بـ JSON فقط بهذا الشكل الدقيق:
{"action": "tap", "x": 200, "y": 500, "reason": "سبب الإجراء", "completed": false}

الإجراءات المتاحة: tap, long_press, type, swipe, scroll, back, home, recents, wait, complete, clear_text
- type: أضف "text": "النص المطلوب كتابته"
- swipe/scroll: أضف "direction": "up|down|left|right"
- complete: عندما تنتهي المهمة بنجاح اضبط "completed": true
- استخدم قيم x و y من قائمة العناصر مباشرةً (هي إحداثيات البكسل الفعلية)
- أجب بـ JSON فقط بدون ``` أو أي نص إضافي
    """.trimIndent()

    private fun buildUserPromptForUITree(taskDescription: String, uiTree: String): String = """
المهمة: $taskDescription

عناصر الشاشة الحالية:
$uiTree

ما الإجراء التالي؟ أجب بـ JSON فقط.
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════════
    // Screenshot-based analysis (vision — fallback)
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildSystemPrompt(): String = """
أنت مساعد ذكاء اصطناعي متخصص في التحكم بشاشة الهاتف Android.
تقوم بتحليل لقطات الشاشة وتقرر الإجراء التالي لإكمال المهمة.

قواعد مهمة:
1. أعطِ دائماً إجابة بتنسيق JSON صحيح فقط، بدون أي نص إضافي قبل أو بعد JSON
2. يجب أن تكون قيم x وy أعداداً صحيحة تمثل إحداثيات البكسل الفعلية على الشاشة
   مثال صحيح: "x": 540، "y": 960
   لا تستخدم أبداً أعداداً عشرية أو قيماً مُعيَّرة بين 0.0 و1.0
3. حدد الإحداثيات بدقة بناءً على العناصر المرئية في الشاشة
4. إذا اكتملت المهمة، اضبط "completed" على true

تنسيق الإجابة (JSON فقط بدون ``` أو أي علامات إضافية):
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
أعطِ إجابة JSON فقط بدون أي نص إضافي.
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

    /**
     * استخراج JSON من نص الاستجابة مع دعم كتل ``` و ```json
     */
    private fun extractJson(text: String): String {
        val cleaned = text
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start)
            cleaned.substring(start, end + 1)
        else
            cleaned
    }

    /**
     * الحصول على أبعاد الشاشة الفعلية بالبكسل
     */
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

        /**
         * تحويل إحداثي إلى بكسل:
         * إذا كانت القيمة بين 0.0 و1.0 (مُعيَّرة) تُضرب في حجم الشاشة
         * وإلا تُستخدم كما هي (قيمة بكسل مباشرة)
         */
        fun Double.toPixelX(): Float =
            if (this in 0.0..1.0) (this * screenW).toFloat() else this.toFloat()

        fun Double.toPixelY(): Float =
            if (this in 0.0..1.0) (this * screenH).toFloat() else this.toFloat()

        when (action.action) {
            AIAction.ACTION_TAP -> {
                val x = action.x?.toPixelX() ?: return
                val y = action.y?.toPixelY() ?: return
                service.performTap(x, y)
            }
            AIAction.ACTION_LONG_PRESS -> {
                val x = action.x?.toPixelX() ?: return
                val y = action.y?.toPixelY() ?: return
                service.performLongPress(x, y)
            }
            AIAction.ACTION_TYPE -> action.text?.let { service.typeText(it) }
            AIAction.ACTION_SWIPE -> {
                val x = action.x?.toPixelX() ?: return
                val y = action.y?.toPixelY() ?: return
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
        val maxSide = 768
        val scaled = if (bitmap.width > maxSide || bitmap.height > maxSide) {
            val ratio = minOf(maxSide.toFloat() / bitmap.width, maxSide.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else bitmap
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "AIController"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }
}

// Needed import for GLOBAL_ACTION_BACK etc.
private typealias AccessibilityService = android.accessibilityservice.AccessibilityService
