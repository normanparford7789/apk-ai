package com.aicontrol.app.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
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
        setupProviderSpinner()
        setupModelSpinner()
        loadSettings()
        setupListeners()
    }

    // ─── Provider (HuggingFace / OpenAI / Google Gemini) ─────────────────────

    private fun setupProviderSpinner() {
        val providers = arrayOf(
            "🤗 HuggingFace (مجاني)",
            "🔵 OpenAI",
            "🔴 Google Gemini (مجاني - موصى به)"
        )
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProvider.adapter = adapter

        val idx = when (prefs.apiProvider) {
            PreferencesManager.PROVIDER_OPENAI  -> 1
            PreferencesManager.PROVIDER_GEMINI  -> 2
            else                                -> 0
        }
        binding.spinnerProvider.setSelection(idx)

        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.apiProvider = when (position) {
                    1    -> PreferencesManager.PROVIDER_OPENAI
                    2    -> PreferencesManager.PROVIDER_GEMINI
                    else -> PreferencesManager.PROVIDER_HF
                }
                // عند تغيير المزود: ضبط النموذج على الافتراضي الجديد
                prefs.selectedModel = prefs.defaultModel
                updateProviderHint()
                setupModelSpinner()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateProviderHint() {
        when (prefs.apiProvider) {
            PreferencesManager.PROVIDER_HF -> {
                binding.tvApiKeyLabel.text = "🔑 مفتاح HuggingFace API"
                binding.tvApiKeyHint.text = "احصل على مفتاحك المجاني من: huggingface.co/settings/tokens"
                binding.etApiKey.hint = "hf_..."
            }
            PreferencesManager.PROVIDER_OPENAI -> {
                binding.tvApiKeyLabel.text = "🔑 مفتاح OpenAI API"
                binding.tvApiKeyHint.text = "احصل على مفتاحك من: platform.openai.com/api-keys"
                binding.etApiKey.hint = "sk-..."
            }
            PreferencesManager.PROVIDER_GEMINI -> {
                binding.tvApiKeyLabel.text = "🔑 مفتاح Google Gemini API"
                binding.tvApiKeyHint.text = "احصل على مفتاحك المجاني من: aistudio.google.com → Get API Key"
                binding.etApiKey.hint = "AIza..."
            }
        }
    }

    // ─── Model spinner ────────────────────────────────────────────────────────

    private fun setupModelSpinner() {
        val models = prefs.currentModelList

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerModel.adapter = adapter

        val idx = models.indexOf(prefs.selectedModel).coerceAtLeast(0)
        binding.spinnerModel.setSelection(idx)
        if (prefs.selectedModel !in models) prefs.selectedModel = models[0]

        binding.spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.selectedModel = models[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ─── Load saved values ────────────────────────────────────────────────────

    private fun loadSettings() {
        updateProviderHint()
        val apiKey = prefs.openAiApiKey
        if (apiKey.isNotBlank()) {
            binding.etApiKey.setText("••••••••" + apiKey.takeLast(4))
        }
        binding.sliderDelay.value = prefs.actionDelay.toFloat()
        binding.tvDelayValue.text = "${prefs.actionDelay}ms"
        binding.sliderMaxActions.value = prefs.maxActions.toFloat()
        binding.tvMaxActionsValue.text = "${prefs.maxActions} إجراء"
    }

    // ─── Listeners ───────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isBlank() || key.startsWith("••")) {
                Toast.makeText(requireContext(), "أدخل مفتاح API جديد", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // التحقق من الصيغة حسب المزود
            val validationError = when (prefs.apiProvider) {
                PreferencesManager.PROVIDER_HF -> {
                    if (!key.startsWith("hf_"))
                        "مفتاح HuggingFace يجب أن يبدأ بـ hf_"
                    else null
                }
                PreferencesManager.PROVIDER_OPENAI -> {
                    if (!key.startsWith("sk-"))
                        "مفتاح OpenAI يجب أن يبدأ بـ sk-"
                    else null
                }
                PreferencesManager.PROVIDER_GEMINI -> {
                    if (!key.startsWith("AIza"))
                        "مفتاح Google Gemini يجب أن يبدأ بـ AIza"
                    else null
                }
                else -> null
            }

            if (validationError != null) {
                Toast.makeText(requireContext(), validationError, Toast.LENGTH_LONG).show()
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
            val isPassword = binding.etApiKey.inputType ==
                (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            if (isPassword) {
                binding.etApiKey.inputType = InputType.TYPE_CLASS_TEXT
                binding.etApiKey.setText(prefs.openAiApiKey)
                binding.btnToggleVisibility.text = "إخفاء"
            } else {
                binding.etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if (prefs.openAiApiKey.isNotBlank())
                    binding.etApiKey.setText("••••••••" + prefs.openAiApiKey.takeLast(4))
                binding.btnToggleVisibility.text = "إظهار"
            }
        }

        binding.sliderDelay.addOnChangeListener { _, value, _ ->
            prefs.actionDelay = value.toInt()
            binding.tvDelayValue.text = "${value.toInt()}ms"
        }

        binding.sliderMaxActions.addOnChangeListener { _, value, _ ->
            prefs.maxActions = value.toInt()
            binding.tvMaxActionsValue.text = "${value.toInt()} إجراء"
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
