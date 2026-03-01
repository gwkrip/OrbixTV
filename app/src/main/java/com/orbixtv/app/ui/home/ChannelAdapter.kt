package com.orbixtv.app.ui.home

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.orbixtv.app.R
import com.orbixtv.app.data.Channel
import com.orbixtv.app.databinding.ItemChannelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// Cache ping result per channel ID — expired setelah 2 menit
private val pingCache = mutableMapOf<String, Pair<Int, Long>>()
private const val PING_TTL_MS = 2 * 60 * 1000L

class ChannelAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val onChannelClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Channel, newItem: Channel) = oldItem == newItem

        // Warna dot status
        private const val COLOR_ONLINE  = 0xFF4CAF50.toInt() // hijau
        private const val COLOR_OFFLINE = 0xFFFF4444.toInt() // merah
        private const val COLOR_UNKNOWN = 0x44FFFFFF.toInt() // abu transparan
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ChannelViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelPing()
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val cardView: CardView? = binding.root as? CardView
        private var pingJob: Job? = null

        fun bind(channel: Channel) {
            binding.tvChannelName.text = channel.name
            binding.tvGroupName.text = channel.group
            binding.tvStreamType.text = channel.streamType

            // ② Glide dengan disk cache — logo tidak dimuat ulang setiap scroll
            if (channel.logoUrl.isNotEmpty()) {
                Glide.with(binding.ivLogo)
                    .load(channel.logoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .placeholder(R.drawable.ic_tv_placeholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(binding.ivLogo)
            } else {
                binding.ivLogo.setImageResource(R.drawable.ic_tv_placeholder)
            }

            binding.root.setOnClickListener { onChannelClick(channel) }

            // D-pad / TV focus highlight
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1f
                binding.root.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                cardView?.let { cv ->
                    cv.setCardBackgroundColor(
                        cv.context.getColor(if (hasFocus) R.color.bg_surface else R.color.bg_card)
                    )
                    cv.cardElevation = if (hasFocus) 8f else 0f
                }
            }

            // ③ Status dot: cek cache dulu, baru ping jika perlu
            updateStatusDot(channel.id, getPingCache(channel.id))
            schedulePingIfNeeded(channel)
        }

        fun cancelPing() {
            pingJob?.cancel()
            pingJob = null
        }

        private fun updateStatusDot(channelId: String, status: Int) {
            val color = when (status) {
                1  -> COLOR_ONLINE
                -1 -> COLOR_OFFLINE
                else -> COLOR_UNKNOWN
            }
            binding.vStatusDot?.setBackgroundColor(color)
        }

        private fun getPingCache(channelId: String): Int {
            val cached = pingCache[channelId] ?: return 0
            val (status, timestamp) = cached
            return if (System.currentTimeMillis() - timestamp < PING_TTL_MS) status else 0
        }

        private fun schedulePingIfNeeded(channel: Channel) {
            val cached = pingCache[channel.id]
            val isExpired = cached == null ||
                System.currentTimeMillis() - cached.second >= PING_TTL_MS

            if (!isExpired) return // masih valid, tidak perlu ping

            pingJob?.cancel()
            pingJob = lifecycleOwner.lifecycleScope.launch {
                val status = withContext(Dispatchers.IO) { pingChannel(channel.url) }
                pingCache[channel.id] = Pair(status, System.currentTimeMillis())
                // Update dot hanya jika ViewHolder masih terikat ke channel yang sama
                if (bindingAdapterPosition != RecyclerView.NO_ID.toInt()) {
                    updateStatusDot(channel.id, status)
                }
            }
        }

        /** HEAD request ringan — timeout 5 detik */
        private fun pingChannel(url: String): Int {
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) 1 else -1
            } catch (e: Exception) {
                -1
            }
        }
    }
}
