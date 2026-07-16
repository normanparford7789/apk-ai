package com.aicontrol.app.ui.training

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aicontrol.app.R
import com.aicontrol.app.data.TrainingTask
import com.aicontrol.app.databinding.ItemTrainingTaskBinding
import java.text.SimpleDateFormat
import java.util.*

class TrainingAdapter(
    private val onTaskClick: (TrainingTask) -> Unit,
    private val onTaskLongClick: (TrainingTask) -> Unit,
    private val onFavoriteClick: (TrainingTask) -> Unit,
    private val onActivateClick: (TrainingTask) -> Unit
) : ListAdapter<TrainingTask, TrainingAdapter.ViewHolder>(DiffCallback()) {

    private var activeTaskId: Long = -1L

    fun setActiveTaskId(id: Long) {
        activeTaskId = id
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemTrainingTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(task: TrainingTask) {
            binding.tvTitle.text = task.title
            binding.tvDescription.text = task.description
            binding.tvCategory.text = task.category
            binding.tvRunCount.text = "تشغيلات: ${task.runCount}"

            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            binding.tvDate.text = sdf.format(Date(task.createdAt))

            // Active indicator
            val isActive = task.id == activeTaskId
            binding.root.setCardBackgroundColor(
                if (isActive)
                    binding.root.context.getColor(R.color.card_active)
                else
                    binding.root.context.getColor(R.color.card_default)
            )
            binding.tvActiveLabel.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.GONE

            // Favorite
            binding.ivFavorite.setImageResource(
                if (task.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            )

            binding.root.setOnClickListener { onTaskClick(task) }
            binding.root.setOnLongClickListener { onTaskLongClick(task); true }
            binding.ivFavorite.setOnClickListener { onFavoriteClick(task) }
            binding.btnActivate.setOnClickListener { onActivateClick(task) }
            binding.btnActivate.text = if (isActive) "مُفعّل ✓" else "تفعيل"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrainingTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<TrainingTask>() {
        override fun areItemsTheSame(oldItem: TrainingTask, newItem: TrainingTask) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TrainingTask, newItem: TrainingTask) = oldItem == newItem
    }
}
