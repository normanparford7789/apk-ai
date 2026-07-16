package com.aicontrol.app.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.aicontrol.app.R
import com.aicontrol.app.databinding.FragmentSettingsBinding
import com.aicontrol.app.utils.PreferencesManager

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesManager.getInstance(requireContext())
        loadSettings()
        setupListeners()
        setupModelSpinner()
    }

    private fun setupModelSpinner() {
        val models = arrayOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4-vision-preview")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerModel.adapter = adapter

        val currentIndex = models.indexOf(prefs.selectedModel).coerceAtLeast(0)
        binding.spinnerModel.setSelection(currentIndex)
    }

    private fun loadSettings() {
        val apiKey = prefs.openAiApiKey
        if (apiKey.isNotBlank()) {
            // Show masked key
            binding.etApiKey.setText("••••••••" + apiKey.takeLast(4))
        }
        binding.sliderDelay.value = prefs.actionDelay.toFloat()
        binding.tvDelayValue.text = "${prefs.actionDelay}ms"
        binding.sliderMaxActions.value = prefs.maxActions.toFloat()
        binding.tvMaxActionsValue.text = "${prefs.maxActions} إجراء"
    }

    private fun setupListeners() {
        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isBlank() || key.startsWith("••")) {
                Toast.makeText(requireContext(), "أدخل مفتاح API جديد", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!key.startsWith("sk-")) {
                Toast.makeText(requireContext(), "مفتاح API غير صحيح (يجب أن يبدأ بـ sk-)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.openAiApiKey = key
            Toast.makeText(requireContext(), "تم حفظ مفتاح API ✓", Toast.LENGTH_SHORT).show()
            binding.etApiKey.setText("••••••••" + key.takeLast(4))
        }

        binding.btnClearApiKey.setOnClickListener {
            prefs.openAiApiKey = ""
            binding.etApiKey.setText("")
            Toast.makeText(requireContext(), "تم حذف مفتاح API", Toast.LENGTH_SHORT).show()
        }

        binding.btnToggleVisibility.setOnClickListener {
            val currentType = binding.etApiKey.inputType
            if (currentType == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                binding.etApiKey.inputType = InputType.TYPE_CLASS_TEXT
                binding.etApiKey.setText(prefs.openAiApiKey)
                binding.btnToggleVisibility.text = "إخفاء"
            } else {
                binding.etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if (prefs.openAiApiKey.isNotBlank()) {
                    binding.etApiKey.setText("••••••••" + prefs.openAiApiKey.takeLast(4))
                }
                binding.btnToggleVisibility.text = "إظهار"
            }
        }

        binding.sliderDelay.addOnChangeListener { _, value, _ ->
            val intVal = value.toInt()
            prefs.actionDelay = intVal
            binding.tvDelayValue.text = "${intVal}ms"
        }

        binding.sliderMaxActions.addOnChangeListener { _, value, _ ->
            val intVal = value.toInt()
            prefs.maxActions = intVal
            binding.tvMaxActionsValue.text = "$intVal إجراء"
        }

        binding.spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val models = arrayOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4-vision-preview")
                prefs.selectedModel = models[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.btnSaveSettings.setOnClickListener {
            Toast.makeText(requireContext(), "تم حفظ الإعدادات ✓", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
