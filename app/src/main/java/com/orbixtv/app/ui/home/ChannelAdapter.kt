package com.orbixtv.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.orbixtv.app.R
import com.orbixtv.app.data.Channel
import com.orbixtv.app.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Channel, newItem: Channel) = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.tvChannelName.text = channel.name
            binding.tvGroupName.text = channel.group

            if (channel.logoUrl.isNotEmpty()) {
                Glide.with(binding.ivLogo)
                    .load(channel.logoUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.ic_tv_placeholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(binding.ivLogo)
            } else {
                binding.ivLogo.setImageResource(R.drawable.ic_tv_placeholder)
            }

            // Stream type badge
            val streamType = when {
                channel.url.contains(".mpd") -> "DASH"
                channel.url.contains(".m3u8") -> "HLS"
                else -> "LIVE"
            }
            binding.tvStreamType.text = streamType

            binding.root.setOnClickListener { onChannelClick(channel) }
        }
    }
}
