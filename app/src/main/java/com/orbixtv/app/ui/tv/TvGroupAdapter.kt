package com.orbixtv.app.ui.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.orbixtv.app.data.ChannelGroup
import com.orbixtv.app.databinding.ItemGroupTvBinding

class TvGroupAdapter(
    private val onGroupSelected: (ChannelGroup) -> Unit
) : ListAdapter<ChannelGroup, TvGroupAdapter.GroupViewHolder>(DiffCallback) {

    private var selectedPosition = -1

    companion object DiffCallback : DiffUtil.ItemCallback<ChannelGroup>() {
        override fun areItemsTheSame(a: ChannelGroup, b: ChannelGroup) = a.name == b.name
        override fun areContentsTheSame(a: ChannelGroup, b: ChannelGroup) = a == b
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemGroupTvBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    inner class GroupViewHolder(private val binding: ItemGroupTvBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(group: ChannelGroup, isSelected: Boolean) {
            binding.tvGroupName.text    = "${group.flagEmoji} ${group.name}"
            binding.tvChannelCount.text = "${group.channels.size}"

            binding.root.isActivated = isSelected
            binding.root.setBackgroundColor(
                if (isSelected)
                    binding.root.context.getColor(com.orbixtv.app.R.color.bg_card)
                else
                    android.graphics.Color.TRANSPARENT
            )

            binding.tvGroupName.setTextColor(
                binding.root.context.getColor(
                    if (isSelected) com.orbixtv.app.R.color.accent_blue
                    else com.orbixtv.app.R.color.white
                )
            )

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val prev = selectedPosition
                    selectedPosition = pos
                    notifyItemChanged(prev)
                    notifyItemChanged(selectedPosition)
                    onGroupSelected(getItem(pos))
                }
            }

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.03f else 1f
                binding.root.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
        }
    }
}
