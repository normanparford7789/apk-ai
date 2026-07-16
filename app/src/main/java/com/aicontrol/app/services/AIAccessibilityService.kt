package com.aicontrol.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class AIAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Process events as needed
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * Capture screen using the built-in AccessibilityService.takeScreenshot() (API 30+).
     * Falls back to ScreenCaptureService (MediaProjection) on older devices.
     */
    suspend fun captureScreen(): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshotSync()
            } else {
                ScreenCaptureService.instance?.getLatestBitmap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen failed", e)
            ScreenCaptureService.instance?.getLatestBitmap()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotSync(): Bitmap? = suspendCancellableCoroutine { cont ->
        val mainExecutor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    val swBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    bitmap?.recycle()
                    hardwareBuffer.close()
                    if (cont.isActive) cont.resume(swBitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "takeScreenshot onSuccess parse error", e)
                    if (cont.isActive) cont.resume(null)
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.e(TAG, "takeScreenshot failed, errorCode=$errorCode")
                if (cont.isActive) cont.resume(null)
            }
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        cont.invokeOnCancellation { /* nothing to cancel server-side */ }
    }

    /**
     * Tap at specific screen coordinates.
     */
    fun performTap(x: Float, y: Float) {
        if (!buildGestureStroke(x, y, x, y, 50)) {
            Log.w(TAG, "Failed to dispatch tap gesture at ($x, $y)")
        }
        Log.d(TAG, "Tapped at ($x, $y)")
    }

    /**
     * Long press at specific screen coordinates.
     */
    fun performLongPress(x: Float, y: Float) {
        if (!buildGestureStroke(x, y, x, y, 1000)) {
            Log.w(TAG, "Failed to dispatch long press at ($x, $y)")
        }
    }

    /**
     * Swipe from a point in a direction.
     */
    fun performSwipe(fromX: Float, fromY: Float, direction: String) {
        val distance = 500f
        val (toX, toY) = when (direction.lowercase()) {
            "up"    -> fromX to (fromY - distance)
            "down"  -> fromX to (fromY + distance)
            "left"  -> (fromX - distance) to fromY
            "right" -> (fromX + distance) to fromY
            else    -> return
        }
        buildGestureStroke(fromX, fromY, toX, toY, 300)
    }

    /**
     * Scroll the screen in a given direction using center-screen swipe.
     */
    fun performScroll(direction: String) {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        performSwipe(cx, cy, direction)
    }

    private fun buildGestureStroke(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Type text into the currently focused editable field.
     */
    fun typeText(text: String) {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            // Fallback: paste via clipboard
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("ai_text", text))
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    /**
     * Clear text from the currently focused editable field.
     */
    fun clearText() {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    /**
     * Get all readable text currently on screen (legacy simple version).
     */
    fun getScreenContent(): String {
        val sb = StringBuilder()
        val root = rootInActiveWindow ?: return ""
        extractText(root, sb)
        return sb.toString()
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        if (!node.text.isNullOrEmpty()) sb.append(node.text).append('\n')
        if (!node.contentDescription.isNullOrEmpty()) sb.append('[').append(node.contentDescription).append("]\n")
        repeat(node.childCount) { extractText(node.getChild(it), sb) }
    }

    /**
     * Returns a rich, structured text representation of the current UI tree.
     * Each element: TYPE: "label" [x=CX, y=CY] {attributes}
     * This is the lightweight alternative to sending screenshots — reduces token usage by ~99%.
     */
    fun getUITree(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val pkg = root.packageName?.toString() ?: "unknown"
        sb.append("Package: $pkg\n")
        val count = intArrayOf(0)
        extractNode(root, sb, 0, count)
        return sb.toString().trim()
    }

    private fun extractNode(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        depth: Int,
        count: IntArray
    ) {
        node ?: return
        if (count[0] >= MAX_UI_NODES) return
        if (depth > MAX_UI_DEPTH) return

        val text = node.text?.toString()?.trim()?.take(100) ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.take(100) ?: ""
        val label = text.ifEmpty { desc }
        val hasInfo = label.isNotEmpty() || node.isClickable || node.isScrollable || node.isEditable

        if (hasInfo) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val cx = bounds.centerX()
            val cy = bounds.centerY()

            // Only include on-screen elements with valid coordinates
            if (cx > 0 && cy > 0) {
                val type = node.className?.toString()?.substringAfterLast('.') ?: "View"
                val attrs = buildString {
                    if (node.isClickable) append(" clickable")
                    if (node.isScrollable) append(" scrollable")
                    if (node.isEditable) append(" editable")
                    if (node.isFocused) append(" focused")
                    if (node.isChecked) append(" checked")
                    if (!node.isEnabled) append(" disabled")
                }
                sb.append("$type: \"$label\" [x=$cx, y=$cy]$attrs\n")
                count[0]++
            }
        }

        repeat(node.childCount) { i -> extractNode(node.getChild(i), sb, depth + 1, count) }
    }

    companion object {
        private const val TAG = "AIAccessibilityService"
        private const val MAX_UI_NODES = 150
        private const val MAX_UI_DEPTH = 15

        @Volatile
        var instance: AIAccessibilityService? = null
            private set

        val isActive: Boolean get() = instance != null
    }
}
