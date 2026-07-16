package com.aicontrol.app.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.aicontrol.app.App
import com.aicontrol.app.MainActivity
import com.aicontrol.app.R
import com.aicontrol.app.ai.AIController
import com.aicontrol.app.ai.ChatController
import com.aicontrol.app.data.ActionHistory
import com.aicontrol.app.data.TrainingRepository
import com.aicontrol.app.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var controlPanel: View? = null
    private lateinit var prefs: PreferencesManager
    private var aiController: AIController? = null
    private var chatController: ChatController? = null
    private var repository: TrainingRepository? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isControlPanelVisible = false
    private var activeTaskName = "AI Control"
    private var chatContainer: LinearLayout? = null
    private var chatScrollView: ScrollView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager.getInstance(this)
        repository = TrainingRepository.getInstance(this)
        aiController = AIController(this)
        chatController = ChatController(this)
        setupAICallbacks()
        setupChatCallbacks()
        createFloatingButton()
        instance = this
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun setupAICallbacks() {
        aiController?.onStatusUpdate = { status ->
            runOnMainThread { updateStatus(status) }
        }
        aiController?.onActionExecuted = { action ->
            scope.launch(Dispatchers.IO) {
                val taskId = prefs.activeTaskId
                if (taskId != -1L) {
                    repository?.insertHistory(
                        ActionHistory(
                            taskId = taskId,
                            actionType = action.action,
                            description = action.reason
                        )
                    )
                }
            }
        }
        aiController?.onCompleted = { success, message ->
            runOnMainThread {
                updateStatus(message)
                if (success) {
                    scope.launch(Dispatchers.IO) {
                        val taskId = prefs.activeTaskId
                        if (taskId != -1L) {
                            repository?.incrementSuccessCount(taskId)
                        }
                    }
                }
            }
        }
    }

    private fun setupChatCallbacks() {
        chatController?.onChatResponse = { response ->
            runOnMainThread {
                addChatBubble(response, isUser = false)
                setChatLoading(false)
            }
        }
        chatController?.onChatError = { error ->
            runOnMainThread {
                addChatBubble("خطأ: $error", isUser = false)
                setChatLoading(false)
            }
        }
    }

    private fun createFloatingButton() {
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.overlay_floating_button, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.floatX
            y = prefs.floatY
        }

        val fabButton = floatingView!!.findViewById<ImageView>(R.id.fab_ai)

        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isDragging = false

        fabButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleControlPanel()
                    } else {
                        prefs.floatX = params.x
                        prefs.floatY = params.y
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
    }

    private fun toggleControlPanel() {
        if (isControlPanelVisible) {
            hideControlPanel()
        } else {
            showControlPanel()
        }
    }

    private fun showControlPanel() {
        if (isControlPanelVisible) return
        isControlPanelVisible = true

        val inflater = LayoutInflater.from(this)
        controlPanel = inflater.inflate(R.layout.overlay_control_panel, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        setupControlPanel()
        windowManager.addView(controlPanel, params)
    }

    private fun setupControlPanel() {
        val panel = controlPanel ?: return

        val tvStatus = panel.findViewById<TextView>(R.id.tv_status)
        val tvTask = panel.findViewById<TextView>(R.id.tv_task_name)
        val btnClose = panel.findViewById<ImageView>(R.id.btn_close_panel)
        val btnOpenApp = panel.findViewById<TextView>(R.id.btn_open_app)
        val btnClearChat = panel.findViewById<TextView>(R.id.btn_clear_chat)
        val etInput = panel.findViewById<EditText>(R.id.et_chat_input)
        val btnSend = panel.findViewById<ImageButton>(R.id.btn_send)

        chatContainer = panel.findViewById(R.id.chat_container)
        chatScrollView = panel.findViewById(R.id.scroll_chat)

        tvTask.text = activeTaskName

        btnClose.setOnClickListener { hideControlPanel() }

        btnOpenApp.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            hideControlPanel()
        }

        btnClearChat.setOnClickListener {
            chatController?.clearHistory()
            chatContainer?.removeAllViews()
            addChatBubble("تم مسح المحادثة. كيف يمكنني مساعدتك؟", isUser = false)
        }

        btnSend.setOnClickListener {
            val msg = etInput.text.toString().trim()
            if (msg.isNotEmpty()) {
                sendChatMessage(msg)
                etInput.text.clear()
            }
        }

        etInput.setOnEditorActionListener { v, _, _ ->
            val msg = v.text.toString().trim()
            if (msg.isNotEmpty()) {
                sendChatMessage(msg)
                v.text.clear()
            }
            true
        }

        // Welcome message
        addChatBubble("مرحباً! اكتب أمراً وسأقوم بتحليل الشاشة وتنفيذه. مثال: «اضغط على زر الإعدادات»", isUser = false)

        updateStatus(if (aiController?.isActive == true) "الذكاء الاصطناعي يعمل..." else "في وضع الاستعداد")
    }

    private fun sendChatMessage(message: String) {
        addChatBubble(message, isUser = true)
        setChatLoading(true)

        scope.launch(Dispatchers.IO) {
            val response = chatController?.sendMessage(message, withScreen = true) ?: "خطأ: لا يوجد متحكم"

            // If the response looks like an action instruction, also execute it via AIController
            val lower = response.lowercase()
            if (lower.contains("tap") || lower.contains("click") || lower.contains("اضغط") ||
                lower.contains("type") || lower.contains("اكتب") || lower.contains("scroll") ||
                lower.contains("اسحب") || lower.contains("back") || lower.contains("رجوع")) {

                withContext(Dispatchers.Main) {
                    updateStatus("جاري تنفيذ الإجراء...")
                }

                aiController?.start(message)
            }
        }
    }

    private fun addChatBubble(text: String, isUser: Boolean) {
        val container = chatContainer ?: return
        val inflater = LayoutInflater.from(this)

        val bubbleView = inflater.inflate(R.layout.chat_bubble_item, container, false)
        val tvText = bubbleView.findViewById<TextView>(R.id.tv_bubble_text)

        tvText.text = text
        tvText.setBackgroundResource(
            if (isUser) R.drawable.chat_bubble_user else R.drawable.chat_bubble_ai
        )
        tvText.setTextColor(if (isUser) Color.WHITE else Color.parseColor("#1A1A2E"))

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            setMargins(0, 4, 0, 4)
        }
        bubbleView.layoutParams = params
        container.addView(bubbleView)

        // Auto-scroll to bottom
        chatScrollView?.post { chatScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setChatLoading(loading: Boolean) {
        val panel = controlPanel ?: return
        val btnSend = panel.findViewById<ImageButton>(R.id.btn_send)
        val etInput = panel.findViewById<EditText>(R.id.et_chat_input)

        btnSend.isEnabled = !loading
        etInput.isEnabled = !loading

        if (loading) {
            updateStatus("جاري التفكير...")
        } else {
            updateStatus("في وضع الاستعداد")
        }
    }

    private fun updateStatus(status: String) {
        controlPanel?.findViewById<TextView>(R.id.tv_status)?.text = status
    }

    private fun hideControlPanel() {
        isControlPanelVisible = false
        controlPanel?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
        }
        controlPanel = null
        chatContainer = null
        chatScrollView = null
    }

    private fun runOnMainThread(action: () -> Unit) {
        android.os.Handler(mainLooper).post(action)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingButtonService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.CHANNEL_FLOATING)
            .setContentTitle("AI Control - جاهز")
            .setContentText("اضغط على الأيقونة العائمة للدردشة مع الذكاء الاصطناعي")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "إيقاف", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        aiController?.stop()
        scope.cancel()
        floatingView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        hideControlPanel()
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "FloatingButtonService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.aicontrol.app.STOP_FLOATING"

        @Volatile
        var instance: FloatingButtonService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingButtonService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }

        fun isRunning(): Boolean = instance != null
    }
}
