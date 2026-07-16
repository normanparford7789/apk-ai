package com.aicontrol.app.ui.training

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aicontrol.app.R
import com.aicontrol.app.ai.ChatController
import com.aicontrol.app.data.TrainingRepository
import com.aicontrol.app.data.TrainingTask
import com.aicontrol.app.databinding.FragmentTrainingBinding
import com.aicontrol.app.databinding.DialogAddTaskBinding
import com.aicontrol.app.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrainingFragment : Fragment() {

    private var _binding: FragmentTrainingBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: TrainingRepository
    private lateinit var prefs: PreferencesManager
    private lateinit var adapter: TrainingAdapter
    private var chatController: ChatController? = null

    private var chatDialog: AlertDialog? = null
    private var chatContainer: LinearLayout? = null
    private var chatScrollView: ScrollView? = null
    private var chatInput: EditText? = null
    private var chatProgress: ProgressBar? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTrainingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TrainingRepository.getInstance(requireContext())
        prefs = PreferencesManager.getInstance(requireContext())
        chatController = ChatController(requireContext())
        setupRecyclerView()
        setupFabs()
        observeTasks()
    }

    private fun setupRecyclerView() {
        adapter = TrainingAdapter(
            onTaskClick = { task -> showEditDialog(task) },
            onTaskLongClick = { task -> showTaskOptions(task) },
            onFavoriteClick = { task ->
                lifecycleScope.launch {
                    repository.setFavorite(task.id, !task.isFavorite)
                }
            },
            onActivateClick = { task ->
                prefs.activeTaskId = task.id
                adapter.setActiveTaskId(task.id)
                Toast.makeText(requireContext(), "تم تفعيل: ${task.title}", Toast.LENGTH_SHORT).show()
            }
        )
        binding.recyclerTasks.adapter = adapter
        binding.recyclerTasks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
    }

    private fun setupFabs() {
        binding.fabAddTask.setOnClickListener { showAddDialog() }
        binding.fabChat.setOnClickListener { showChatDialog() }
    }

    private fun observeTasks() {
        repository.allTasks.observe(viewLifecycleOwner) { tasks ->
            adapter.submitList(tasks)
            adapter.setActiveTaskId(prefs.activeTaskId)
            binding.tvEmptyState.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerTasks.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Chat Training Dialog
    // ═══════════════════════════════════════════════════════════════════════

    private fun showChatDialog() {
        chatController?.clearHistory()

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_chat_training, null)

        chatContainer = dialogView.findViewById(R.id.chat_container)
        chatScrollView = dialogView.findViewById(R.id.scroll_chat)
        chatInput = dialogView.findViewById(R.id.et_chat_input)
        chatProgress = dialogView.findViewById(R.id.progress_chat)
        val btnSend = dialogView.findViewById<ImageButton>(R.id.btn_send)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btn_close_chat)
        val btnSave = dialogView.findViewById<TextView>(R.id.btn_save_task)

        chatDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
            .also {
                it.setOnShowListener { _ ->
                    it.window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }
        chatDialog?.show()

        btnClose.setOnClickListener { chatDialog?.dismiss() }

        btnSend.setOnClickListener { sendChatMessage() }
        chatInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendChatMessage()
                true
            } else {
                false
            }
        }

        btnSave.setOnClickListener { saveTaskFromChat() }

        addChatBubble("مرحباً! صِف لي ما تريد من الذكاء الاصطناعي أن يفعل. مثال: «بدي يقرا الأسئلة على الشاشة ويختار الجواب الصحيح»", isUser = false)
    }

    private fun sendChatMessage() {
        val msg = chatInput?.text?.toString()?.trim() ?: ""
        if (msg.isEmpty()) return

        chatInput?.setText("")
        addChatBubble(msg, isUser = true)
        setChatLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                chatController?.sendTrainingMessage(msg) ?: "خطأ"
            }
            addChatBubble(response, isUser = false)
            setChatLoading(false)
        }
    }

    private fun saveTaskFromChat() {
        setChatLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            val extracted = withContext(Dispatchers.IO) {
                chatController?.extractTaskFromConversation()
            }

            setChatLoading(false)

            if (extracted == null) {
                Toast.makeText(requireContext(), "تعذر استخراج المهمة من المحادثة", Toast.LENGTH_SHORT).show()
                return@launch
            }

            withContext(Dispatchers.Main) {
                chatDialog?.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.insertTask(
                        TrainingTask(
                            title = extracted.title,
                            description = extracted.description,
                            category = "محادثة"
                        )
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "تم حفظ المهمة: ${extracted.title}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun addChatBubble(text: String, isUser: Boolean) {
        val container = chatContainer ?: return
        val inflater = LayoutInflater.from(requireContext())

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

        chatScrollView?.post { chatScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setChatLoading(loading: Boolean) {
        chatProgress?.visibility = if (loading) View.VISIBLE else View.GONE
        chatInput?.isEnabled = !loading
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Manual add/edit
    // ═══════════════════════════════════════════════════════════════════════

    private fun showAddDialog() {
        showTaskDialog(null)
    }

    private fun showEditDialog(task: TrainingTask) {
        showTaskDialog(task)
    }

    private fun showTaskDialog(task: TrainingTask?) {
        val dialogBinding = DialogAddTaskBinding.inflate(LayoutInflater.from(requireContext()))

        task?.let {
            dialogBinding.etTitle.setText(it.title)
            dialogBinding.etDescription.setText(it.description)
            dialogBinding.etCategory.setText(it.category)
        }

        val title = if (task == null) "إضافة مهمة جديدة" else "تعديل المهمة"
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton("حفظ") { _, _ ->
                val newTitle = dialogBinding.etTitle.text.toString().trim()
                val description = dialogBinding.etDescription.text.toString().trim()
                val category = dialogBinding.etCategory.text.toString().trim().ifEmpty { "عام" }

                if (newTitle.isEmpty() || description.isEmpty()) {
                    Toast.makeText(requireContext(), "يرجى ملء جميع الحقول", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    if (task == null) {
                        repository.insertTask(
                            TrainingTask(
                                title = newTitle,
                                description = description,
                                category = category
                            )
                        )
                        Toast.makeText(requireContext(), "تم إضافة المهمة", Toast.LENGTH_SHORT).show()
                    } else {
                        repository.updateTask(
                            task.copy(
                                title = newTitle,
                                description = description,
                                category = category
                            )
                        )
                        Toast.makeText(requireContext(), "تم تحديث المهمة", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showTaskOptions(task: TrainingTask) {
        val options = arrayOf("تفعيل", "تعديل", "حذف")
        AlertDialog.Builder(requireContext())
            .setTitle(task.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        prefs.activeTaskId = task.id
                        adapter.setActiveTaskId(task.id)
                        Toast.makeText(requireContext(), "تم تفعيل: ${task.title}", Toast.LENGTH_SHORT).show()
                    }
                    1 -> showEditDialog(task)
                    2 -> confirmDelete(task)
                }
            }
            .show()
    }

    private fun confirmDelete(task: TrainingTask) {
        AlertDialog.Builder(requireContext())
            .setTitle("حذف المهمة")
            .setMessage("هل تريد حذف \"${task.title}\"؟")
            .setPositiveButton("حذف") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteTask(task)
                    Toast.makeText(requireContext(), "تم الحذف", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chatDialog?.dismiss()
        _binding = null
    }
}
