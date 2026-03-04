package com.orbixtv.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.facebook.shimmer.ShimmerDrawable
import com.orbixtv.app.R
import com.orbixtv.app.data.Channel
import com.orbixtv.app.databinding.ItemChannelBinding

// pingCache dan logika ping status-dot dihapus:
// layout TV (item_channel.xml) tidak memiliki v_status_dot,
// dan melakukan network ping per-item boros resource di TV.
/** Bersihkan cache ping — dipanggil saat playlist reload (no-op di TV, tetap tersedia untuk kompatibilitas). */
fun clearPingCache() { /* tidak ada cache ping di versi TV */ }

/** ShimmerDrawable sekali buat, dipakai sebagai placeholder logo di semua item */
private val shimmerPlaceholder: ShimmerDrawable by lazy {
    ShimmerDrawable().apply {
        setShimmer(
            com.facebook.shimmer.Shimmer.AlphaHighlightBuilder()
                .setDuration(1200)
                .setBaseAlpha(0.3f)
                .setHighlightAlpha(0.6f)
                .setDirection(com.facebook.shimmer.Shimmer.Direction.LEFT_TO_RIGHT)
                .setAutoStart(true)
                .build()
        )
    }
}

class ChannelAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val onChannelClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(a: Channel, b: Channel) = a.id == b.id
        override fun areContentsTheSame(a: Channel, b: Channel) = a == b
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val cardView: CardView = binding.root

        fun bind(channel: Channel) {
            binding.tvChannelName.text = channel.name
            binding.tvGroupName.text   = channel.group
            binding.tvStreamType.text  = channel.streamType
            applyStreamTypeBadge(channel.streamType)

            if (channel.logoUrl.isNotEmpty()) {
                Glide.with(binding.ivLogo)
                    .load(channel.logoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .placeholder(shimmerPlaceholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(binding.ivLogo)
            } else {
                Glide.with(binding.ivLogo).clear(binding.ivLogo)
                binding.ivLogo.setImageResource(R.drawable.ic_tv_placeholder)
            }

            binding.root.setOnClickListener { onChannelClick(channel) }

            // Efek fokus D-pad: scale naik + warna card berubah
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1f
                binding.root.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                cardView.setCardBackgroundColor(
                    cardView.context.getColor(if (hasFocus) R.color.bg_surface else R.color.bg_card)
                )
                cardView.cardElevation = if (hasFocus) 8f else 0f
            }
        }

        private fun applyStreamTypeBadge(streamType: String) {
            val (bgRes, textColor) = when (streamType.uppercase()) {
                "HLS"         -> R.drawable.bg_badge_hls         to 0xFF4D9EFF.toInt()
                "DASH"        -> R.drawable.bg_badge_dash        to 0xFFAA77FF.toInt()
                "RTMP"        -> R.drawable.bg_badge_rtmp        to 0xFFFF6633.toInt()
                "PROGRESSIVE" -> R.drawable.bg_badge_progressive to 0xFF44CC77.toInt()
                else          -> R.drawable.bg_badge_default     to 0xFF889AAA.toInt()
            }
            binding.tvStreamType.setBackgroundResource(bgRes)
            binding.tvStreamType.setTextColor(textColor)
        }
    }
}
