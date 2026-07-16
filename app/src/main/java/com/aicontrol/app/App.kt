package com.aicontrol.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Floating service channel
            val floatingChannel = NotificationChannel(
                CHANNEL_FLOATING,
                "خدمة التحكم العائمة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار خدمة الأيقونة العائمة"
                setShowBadge(false)
            }

            // AI control channel
            val aiChannel = NotificationChannel(
                CHANNEL_AI,
                "تحكم الذكاء الاصطناعي",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "إشعارات عمل الذكاء الاصطناعي"
            }

            // Screen capture channel
            val captureChannel = NotificationChannel(
                CHANNEL_CAPTURE,
                "التقاط الشاشة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة التقاط الشاشة"
                setShowBadge(false)
            }

            manager.createNotificationChannels(listOf(floatingChannel, aiChannel, captureChannel))
        }
    }

    companion object {
        const val CHANNEL_FLOATING = "floating_service"
        const val CHANNEL_AI = "ai_control"
        const val CHANNEL_CAPTURE = "screen_capture"

        lateinit var instance: App
            private set
    }
}
