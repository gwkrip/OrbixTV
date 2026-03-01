package com.orbixtv.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.orbixtv.app.data.Channel
import com.orbixtv.app.data.ChannelGroup
import com.orbixtv.app.databinding.ItemGroupBinding

class GroupAdapter(
    private val onChannelClick: (Channel) -> Unit
) : ListAdapter<ChannelGroup, GroupAdapter.GroupViewHolder>(DiffCallback) {

    private val expandedGroups = mutableSetOf<String>()

    companion object DiffCallback : DiffUtil.ItemCallback<ChannelGroup>() {
        override fun areItemsTheSame(oldItem: ChannelGroup, newItem: ChannelGroup) = oldItem.name == newItem.name
        override fun areContentsTheSame(oldItem: ChannelGroup, newItem: ChannelGroup) = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GroupViewHolder(
        private val binding: ItemGroupBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val channelAdapter = ChannelAdapter(onChannelClick)

        init {
            binding.rvChannels.apply {
                layoutManager = LinearLayoutManager(itemView.context)
                adapter = channelAdapter
                isNestedScrollingEnabled = false
            }
        }

        fun bind(group: ChannelGroup) {
            val isExpanded = expandedGroups.contains(group.name)

            binding.tvGroupName.text = "${group.flagEmoji} ${group.name}"
            binding.tvChannelCount.text = "${group.channels.size} saluran"
            binding.ivArrow.rotation = if (isExpanded) 180f else 0f
            binding.rvChannels.visibility = if (isExpanded) View.VISIBLE else View.GONE

            channelAdapter.submitList(group.channels)

            binding.headerLayout.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val grp = getItem(pos)
                    if (expandedGroups.contains(grp.name)) {
                        expandedGroups.remove(grp.name)
                        binding.rvChannels.visibility = View.GONE
                        binding.ivArrow.animate().rotation(0f).setDuration(200).start()
                    } else {
                        expandedGroups.add(grp.name)
                        binding.rvChannels.visibility = View.VISIBLE
                        binding.ivArrow.animate().rotation(180f).setDuration(200).start()
                    }
                }
            }
        }
    }
}
