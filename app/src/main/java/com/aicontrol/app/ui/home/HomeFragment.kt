package com.aicontrol.app.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aicontrol.app.MainActivity
import com.aicontrol.app.R
import com.aicontrol.app.data.TrainingRepository
import com.aicontrol.app.databinding.FragmentHomeBinding
import com.aicontrol.app.services.AIAccessibilityService
import com.aicontrol.app.services.FloatingButtonService
import com.aicontrol.app.utils.PermissionHelper
import com.aicontrol.app.utils.PreferencesManager
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: TrainingRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesManager.getInstance(requireContext())
        repository = TrainingRepository.getInstance(requireContext())
        setupClickListeners()
        updateStats()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateServiceStatus()
    }

    private fun setupClickListeners() {
        // Start/Stop floating service
        binding.btnToggleService.setOnClickListener {
            if (FloatingButtonService.isRunning()) {
                (activity as? MainActivity)?.stopFloatingService()
            } else {
                (activity as? MainActivity)?.startFloatingService()
            }
        }

        // Permission cards
        binding.cardOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                (activity as? MainActivity)?.requestOverlayPermission()
            }
        }

        binding.cardAccessibility.setOnClickListener {
            if (!PermissionHelper.hasAccessibilityPermission(requireContext())) {
                (activity as? MainActivity)?.requestAccessibilityPermission()
            }
        }

        binding.cardApiKey.setOnClickListener {
            // Navigate to settings
            requireActivity().findNavController(R.id.nav_host_fragment)
                .navigate(R.id.settingsFragment)
        }
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(requireContext())
        val hasAccessibility = PermissionHelper.hasAccessibilityPermission(requireContext())
        val hasApiKey = prefs.openAiApiKey.isNotBlank()

        binding.ivOverlayStatus.setImageResource(
            if (hasOverlay) R.drawable.ic_check_circle else R.drawable.ic_warning
        )
        binding.tvOverlayStatus.text = if (hasOverlay) "مُفعّل ✓" else "اضغط لتفعيل"

        binding.ivAccessibilityStatus.setImageResource(
            if (hasAccessibility) R.drawable.ic_check_circle else R.drawable.ic_warning
        )
        binding.tvAccessibilityStatus.text = if (hasAccessibility) "مُفعّل ✓" else "اضغط لتفعيل"

        binding.ivApiStatus.setImageResource(
            if (hasApiKey) R.drawable.ic_check_circle else R.drawable.ic_warning
        )
        binding.tvApiStatus.text = if (hasApiKey) "مُضاف ✓" else "اضغط للإضافة"

        // Overall readiness
        val isReady = hasOverlay && hasAccessibility && hasApiKey
        binding.tvReadyStatus.text = if (isReady) "✅ جاهز للعمل" else "⚠️ يتطلب إعداد"
        binding.tvReadyStatus.setTextColor(
            if (isReady)
                requireContext().getColor(R.color.status_active)
            else
                requireContext().getColor(R.color.status_inactive)
        )
    }

    private fun updateServiceStatus() {
        val isRunning = FloatingButtonService.isRunning()
        binding.btnToggleService.text = if (isRunning) "إيقاف الخدمة العائمة" else "تشغيل الخدمة العائمة"
        binding.tvServiceStatus.text = if (isRunning) "● الخدمة تعمل" else "○ الخدمة متوقفة"
        binding.tvServiceStatus.setTextColor(
            if (isRunning)
                requireContext().getColor(R.color.status_active)
            else
                requireContext().getColor(R.color.status_inactive)
        )
    }

    private fun updateStats() {
        lifecycleScope.launch {
            repository.allTasks.observe(viewLifecycleOwner) { tasks ->
                binding.tvTaskCount.text = "${tasks.size}"
                binding.tvFavoriteCount.text = "${tasks.count { it.isFavorite }}"
                binding.tvRunCount.text = "${tasks.sumOf { it.runCount }}"
            }
        }
    }

    private fun androidx.fragment.app.Fragment.findNavController(id: Int): androidx.navigation.NavController {
        return androidx.navigation.Navigation.findNavController(requireActivity(), id)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
