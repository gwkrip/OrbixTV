package com.orbixtv.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.cardview.widget.CardView
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

        // FIX: cast root ke CardView dengan aman menggunakan as?
        private val cardView: CardView? = binding.root as? CardView

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

            // #10: Gunakan streamType yang sudah dihitung saat parsing — tidak komputasi ulang
            binding.tvStreamType.text = channel.streamType

            binding.root.setOnClickListener { onChannelClick(channel) }

            // D-pad / TV focus: scale + highlight CardView saat fokus
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1f
                binding.root.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()

                // FIX: cardElevation harus Float (8f bukan 8)
                cardView?.let { cv ->
                    cv.setCardBackgroundColor(
                        cv.context.getColor(
                            if (hasFocus) R.color.bg_surface else R.color.bg_card
                        )
                    )
                    cv.cardElevation = if (hasFocus) 8f else 0f
                }
            }
        }
    }
}
