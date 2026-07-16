package com.aicontrol.app.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aicontrol.app.data.ActionHistory
import com.aicontrol.app.data.TrainingRepository
import com.aicontrol.app.databinding.FragmentHistoryBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: TrainingRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TrainingRepository.getInstance(requireContext())
        setupRecyclerView()
        observeHistory()
    }

    private fun setupRecyclerView() {
        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun observeHistory() {
        repository.allHistory.observe(viewLifecycleOwner) { history ->
            val adapter = HistoryAdapter(history)
            binding.recyclerHistory.adapter = adapter
            binding.tvEmptyHistory.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
            binding.tvTotalActions.text = "إجمالي الإجراءات: ${history.size}"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class HistoryAdapter(private val items: List<ActionHistory>) :
    androidx.recyclerview.widget.RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvAction: android.widget.TextView = view.findViewById(com.aicontrol.app.R.id.tv_action_type)
        val tvDescription: android.widget.TextView = view.findViewById(com.aicontrol.app.R.id.tv_action_description)
        val tvTime: android.widget.TextView = view.findViewById(com.aicontrol.app.R.id.tv_action_time)
        val ivStatus: android.widget.ImageView = view.findViewById(com.aicontrol.app.R.id.iv_action_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(com.aicontrol.app.R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvAction.text = item.actionType
        holder.tvDescription.text = item.description
        val sdf = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        holder.tvTime.text = sdf.format(Date(item.timestamp))
        holder.ivStatus.setImageResource(
            if (item.success) com.aicontrol.app.R.drawable.ic_check_circle
            else com.aicontrol.app.R.drawable.ic_warning
        )
    }

    override fun getItemCount() = items.size
}
