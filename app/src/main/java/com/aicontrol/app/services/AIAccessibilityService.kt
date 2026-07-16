package com.aicontrol.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
     * Capture screen using ScreenCaptureService (MediaProjection).
     */
    fun captureScreen(): Bitmap? {
        return ScreenCaptureService.instance?.getLatestBitmap()
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
     * Get all readable text currently on screen.
     */
    fun getScreenContent(): String {
        val sb = StringBuilder()
        extractText(rootInActiveWindow, sb)
        return sb.toString()
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        if (!node.text.isNullOrEmpty()) sb.append(node.text).append('\n')
        if (!node.contentDescription.isNullOrEmpty()) sb.append('[').append(node.contentDescription).append("]\n")
        repeat(node.childCount) { extractText(node.getChild(it), sb) }
    }

    companion object {
        private const val TAG = "AIAccessibilityService"

        @Volatile
        var instance: AIAccessibilityService? = null
            private set

        val isActive: Boolean get() = instance != null
    }
}
