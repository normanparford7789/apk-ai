package com.aicontrol.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicontrol.app.utils.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Auto-start floating service if it was enabled before reboot
            // User must explicitly start it from the app
        }
    }
}
