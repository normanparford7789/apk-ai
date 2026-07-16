package com.aicontrol.app.ui.training

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aicontrol.app.R
import com.aicontrol.app.data.TrainingRepository
import com.aicontrol.app.data.TrainingTask
import com.aicontrol.app.databinding.FragmentTrainingBinding
import com.aicontrol.app.databinding.DialogAddTaskBinding
import com.aicontrol.app.utils.PreferencesManager
import kotlinx.coroutines.launch

class TrainingFragment : Fragment() {

    private var _binding: FragmentTrainingBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: TrainingRepository
    private lateinit var prefs: PreferencesManager
    private lateinit var adapter: TrainingAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTrainingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TrainingRepository.getInstance(requireContext())
        prefs = PreferencesManager.getInstance(requireContext())
        setupRecyclerView()
        setupFab()
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

    private fun setupFab() {
        binding.fabAddTask.setOnClickListener {
            showAddDialog()
        }
    }

    private fun observeTasks() {
        repository.allTasks.observe(viewLifecycleOwner) { tasks ->
            adapter.submitList(tasks)
            adapter.setActiveTaskId(prefs.activeTaskId)
            binding.tvEmptyState.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerTasks.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        }
    }

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
                        val id = repository.insertTask(
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
        _binding = null
    }
}
