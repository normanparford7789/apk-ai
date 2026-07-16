package com.aicontrol.app.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.aicontrol.app.App
import com.aicontrol.app.MainActivity
import com.aicontrol.app.R
import com.aicontrol.app.ai.AIController
import com.aicontrol.app.data.ActionHistory
import com.aicontrol.app.data.TrainingRepository
import com.aicontrol.app.data.TrainingTask
import com.aicontrol.app.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var controlPanel: View? = null
    private lateinit var prefs: PreferencesManager
    private var aiController: AIController? = null
    private var repository: TrainingRepository? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isControlPanelVisible = false
    private var toggleBtn: Button? = null
    private var statusText: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager.getInstance(this)
        repository = TrainingRepository.getInstance(this)
        aiController = AIController(this)
        setupAICallbacks()
        createFloatingButton()
        instance = this
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun setupAICallbacks() {
        aiController?.onStatusUpdate = { status ->
            runOnMainThread { statusText?.text = status }
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
                    repository?.incrementRunCount(taskId)
                }
            }
        }
        aiController?.onCompleted = { success, message ->
            runOnMainThread {
                statusText?.text = message
                updateToggleButton()
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

        fabButton.setOnTouchListener { _, event ->
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
        if (isControlPanelVisible) hideControlPanel() else showControlPanel()
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

        toggleBtn = panel.findViewById(R.id.btn_toggle_ai)
        statusText = panel.findViewById(R.id.tv_status)
        val tvTask = panel.findViewById<TextView>(R.id.tv_task_name)
        val btnClose = panel.findViewById<ImageView>(R.id.btn_close_panel)
        val btnOpenApp = panel.findViewById<TextView>(R.id.btn_open_app)

        scope.launch(Dispatchers.IO) {
            val taskId = prefs.activeTaskId
            if (taskId != -1L) {
                val task = repository?.getTaskById(taskId)
                if (task != null) {
                    runOnMainThread { tvTask.text = "🤖 ${task.title}" }
                }
            }
        }

        btnClose.setOnClickListener { hideControlPanel() }

        btnOpenApp.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            hideControlPanel()
        }

        toggleBtn?.setOnClickListener {
            if (aiController?.isActive == true) {
                aiController?.stop()
                updateToggleButton()
            } else {
                startAITask()
            }
        }

        updateToggleButton()
    }

    private fun startAITask() {
        val taskId = prefs.activeTaskId
        if (taskId == -1L) {
            Toast.makeText(this, "فعّل مهمة من التطبيق أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            val task = repository?.getTaskById(taskId)
            if (task == null) {
                runOnMainThread {
                    Toast.makeText(this@FloatingButtonService, "المهمة غير موجودة", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            runOnMainThread {
                aiController?.start(task.description)
                updateToggleButton()
            }
        }
    }

    private fun updateToggleButton() {
        val btn = toggleBtn ?: return
        if (aiController?.isActive == true) {
            btn.text = "⏹ إيقاف الذكاء الاصطناعي"
            btn.setBackgroundResource(R.drawable.btn_stop_bg)
        } else {
            btn.text = "▶ تشغيل الذكاء الاصطناعي"
            btn.setBackgroundResource(R.drawable.btn_start_bg)
        }
    }

    private fun hideControlPanel() {
        isControlPanelVisible = false
        controlPanel?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
        }
        controlPanel = null
        toggleBtn = null
        statusText = null
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
            .setContentText("اضغط على الأيقونة العائمة لتشغيل/إيقاف الذكاء الاصطناعي")
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
