package com.aicontrol.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import com.aicontrol.app.databinding.ActivityMainBinding
import com.aicontrol.app.services.FloatingButtonService
import com.aicontrol.app.utils.PermissionHelper
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
        requestNotificationPermission()
    }

    private fun setupNavigation() {
        val navController = findNavController(R.id.nav_host_fragment)
        val appBarConfig = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.trainingFragment, R.id.historyFragment, R.id.settingsFragment)
        )
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfig)
        NavigationUI.setupWithNavController(binding.bottomNav, navController)
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsOnResume()
    }

    private fun checkPermissionsOnResume() {
        // Notify fragments to update permission status
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("إذن الطبقة العلوية")
                .setMessage("يحتاج التطبيق إذن عرض النوافذ فوق التطبيقات الأخرى لعرض الأيقونة العائمة.")
                .setPositiveButton("منح الإذن") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, PermissionHelper.REQUEST_OVERLAY_PERMISSION)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    fun requestAccessibilityPermission() {
        AlertDialog.Builder(this)
            .setTitle("إذن خدمة المساعدة")
            .setMessage("يحتاج التطبيق تفعيل خدمة المساعدة لكي يتمكن الذكاء الاصطناعي من قراءة الشاشة والتحكم بها.\n\nاذهب إلى الإعدادات > إمكانية الوصول > AI Control > فعّل الخدمة.")
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    fun startFloatingService() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        if (!PermissionHelper.hasAccessibilityPermission(this)) {
            requestAccessibilityPermission()
            return
        }
        FloatingButtonService.start(this)
        Toast.makeText(this, "تم تشغيل الأيقونة العائمة", Toast.LENGTH_SHORT).show()
    }

    fun stopFloatingService() {
        FloatingButtonService.stop(this)
        Toast.makeText(this, "تم إيقاف الأيقونة العائمة", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PermissionHelper.REQUEST_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "تم منح إذن الطبقة العلوية", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return findNavController(R.id.nav_host_fragment).navigateUp() || super.onSupportNavigateUp()
    }
}
