package com.aicontrol.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AIAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle events if needed
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

    // Screenshot using AccessibilityService API (Android 9+)
    @RequiresApi(Build.VERSION_CODES.P)
    fun captureScreen(): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            // Handled via callback
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed: $errorCode")
                        }
                    })
                null // Use alternative method below
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            null
        }
    }

    // Synchronous screenshot using ScreenCaptureService
    fun captureScreenSync(): Bitmap? {
        return ScreenCaptureService.instance?.getLatestBitmap()
    }

    // Tap at coordinates
    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Tapped at ($x, $y)")
    }

    // Long press at coordinates
    fun performLongPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Long pressed at ($x, $y)")
    }

    // Swipe in direction from point
    fun performSwipe(fromX: Float, fromY: Float, direction: String) {
        val toX: Float
        val toY: Float
        when (direction.lowercase()) {
            "up" -> { toX = fromX; toY = fromY - 500f }
            "down" -> { toX = fromX; toY = fromY + 500f }
            "left" -> { toX = fromX - 500f; toY = fromY }
            "right" -> { toX = fromX + 500f; toY = fromY }
            else -> return
        }
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // Scroll in direction
    fun performScroll(direction: String) {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f
        performSwipe(centerX, centerY, direction)
    }

    // Type text into focused element
    fun typeText(text: String) {
        val focused = findFocusedEditText(rootInActiveWindow)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            // Fallback: use clipboard
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("text", text)
            clipboard.setPrimaryClip(clip)
            val focused2 = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focused2?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        Log.d(TAG, "Typed text: $text")
    }

    // Clear text from focused element
    fun clearText() {
        val focused = findFocusedEditText(rootInActiveWindow)
        focused?.let {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    // Find focused EditText
    private fun findFocusedEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val result = findFocusedEditText(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    // Get screen content as text
    fun getScreenContent(): String {
        val builder = StringBuilder()
        extractTextFromNode(rootInActiveWindow, builder)
        return builder.toString()
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?, builder: StringBuilder) {
        node ?: return
        if (!node.text.isNullOrEmpty()) {
            builder.append(node.text).append("\n")
        }
        if (!node.contentDescription.isNullOrEmpty()) {
            builder.append("[${node.contentDescription}]\n")
        }
        for (i in 0 until node.childCount) {
            extractTextFromNode(node.getChild(i), builder)
        }
    }

    companion object {
        private const val TAG = "AIAccessibilityService"

        @Volatile
        var instance: AIAccessibilityService? = null
            private set

        val isActive: Boolean get() = instance != null
    }
}
