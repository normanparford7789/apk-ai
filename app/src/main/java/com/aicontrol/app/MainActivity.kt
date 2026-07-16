package com.aicontrol.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import com.aicontrol.app.databinding.ActivityMainBinding
import com.aicontrol.app.services.FloatingButtonService
import com.aicontrol.app.services.ScreenCaptureService
import com.aicontrol.app.utils.PermissionHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
        requestNotificationPermission()
    }

    // ─── Navigation ─────────────────────────────────────────────────────────
    // Must use NavHostFragment.navController when the host is inside FragmentContainerView
    // Using findNavController(viewId) directly in onCreate() throws IllegalStateException.

    private fun getNavController() =
        (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
            .navController

    private fun setupNavigation() {
        val navController = getNavController()
        val appBarConfig = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.trainingFragment, R.id.historyFragment, R.id.settingsFragment)
        )
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfig)
        NavigationUI.setupWithNavController(binding.bottomNav, navController)
    }

    override fun onSupportNavigateUp(): Boolean =
        getNavController().navigateUp() || super.onSupportNavigateUp()

    // ─── Permissions ─────────────────────────────────────────────────────────

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
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        PermissionHelper.REQUEST_OVERLAY_PERMISSION
                    )
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    fun requestAccessibilityPermission() {
        AlertDialog.Builder(this)
            .setTitle("إذن خدمة المساعدة")
            .setMessage(
                "يحتاج التطبيق تفعيل خدمة المساعدة لكي يتمكن الذكاء الاصطناعي من قراءة الشاشة والتحكم بها.\n\n" +
                "اذهب إلى الإعدادات > إمكانية الوصول > AI Control > فعّل الخدمة."
            )
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    // ─── Floating service ─────────────────────────────────────────────────────

    fun startFloatingService() {
        if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return }
        if (!PermissionHelper.hasAccessibilityPermission(this)) { requestAccessibilityPermission(); return }
        // Request MediaProjection consent so ScreenCaptureService can run as a fallback
        // capture source on devices where the accessibility takeScreenshot() is unavailable.
        if (ScreenCaptureService.instance == null) { requestMediaProjection(); return }
        FloatingButtonService.start(this)
        Toast.makeText(this, "تم تشغيل الأيقونة العائمة ✓", Toast.LENGTH_SHORT).show()
    }

    fun stopFloatingService() {
        FloatingButtonService.stop(this)
        Toast.makeText(this, "تم إيقاف الأيقونة العائمة", Toast.LENGTH_SHORT).show()
    }

    // ─── MediaProjection (screen capture fallback) ────────────────────────────

    fun requestMediaProjection() {
        AlertDialog.Builder(this)
            .setTitle("إذن التقاط الشاشة")
            .setMessage(
                "يحتاج التطبيق إذن التقاط الشاشة لكي يتمكن الذكاء الاصطناعي من رؤية ما على الشاشة وتنفيذ المهام.\n\n" +
                "سيظهر نافذة طلب الأذونات من النظام، اضغط «ابدأ الآن»."
            )
            .setPositiveButton("متابعة") { _, _ ->
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    @Deprecated("Use registerForActivityResult instead")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PermissionHelper.REQUEST_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "تم منح إذن الطبقة العلوية ✓", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    ScreenCaptureService.start(this, resultCode, data)
                    Toast.makeText(this, "تم منح إذن التقاط الشاشة ✓", Toast.LENGTH_SHORT).show()
                    // Now that capture is ready, start the floating service.
                    if (Settings.canDrawOverlays(this) &&
                        PermissionHelper.hasAccessibilityPermission(this)) {
                        FloatingButtonService.start(this)
                    }
                } else {
                    Toast.makeText(this, "تم رفض إذن التقاط الشاشة", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1004
    }
}
