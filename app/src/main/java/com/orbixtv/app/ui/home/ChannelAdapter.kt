package com.orbixtv.app.ui.home

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
import com.orbixtv.app.data.M3uParser
import com.orbixtv.app.databinding.ItemChannelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

private val pingCache = ConcurrentHashMap<String, Pair<Int, Long>>()
private const val PING_TTL_MS = 2 * 60 * 1000L
private val pingSemaphore = Semaphore(6)

private val pingHttpClient = okhttp3.OkHttpClient.Builder()
    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

class ChannelAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val onChannelClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(a: Channel, b: Channel) = a.id == b.id
        override fun areContentsTheSame(a: Channel, b: Channel) = a == b

        private const val COLOR_ONLINE  = 0xFF4CAF50.toInt()
        private const val COLOR_OFFLINE = 0xFFFF4444.toInt()
        private const val COLOR_UNKNOWN = 0x44FFFFFF.toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) =
        holder.bind(getItem(position))

    override fun onViewRecycled(holder: ChannelViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelPing()
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val cardView: CardView = binding.root
        private var pingJob: Job? = null
        private var boundChannelId: String = ""

        fun bind(channel: Channel) {
            boundChannelId = channel.id

            binding.tvChannelName.text = channel.name
            binding.tvGroupName.text   = channel.group
            binding.tvStreamType.text  = channel.streamType
            applyStreamTypeBadge(channel.streamType)

            if (channel.logoUrl.isNotEmpty()) {
                Glide.with(binding.ivLogo)
                    .load(channel.logoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .placeholder(R.drawable.ic_tv_placeholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(binding.ivLogo)
            } else {
                Glide.with(binding.ivLogo).clear(binding.ivLogo)
                binding.ivLogo.setImageResource(R.drawable.ic_tv_placeholder)
            }

            binding.root.setOnClickListener { onChannelClick(channel) }

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

            updateStatusDot(getCachedPingStatus(channel.id))
            schedulePingIfNeeded(channel)
        }

        fun cancelPing() {
            pingJob?.cancel()
            pingJob = null
        }

        private fun applyStreamTypeBadge(streamType: String) {
            val ctx = binding.tvStreamType.context
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

        private fun updateStatusDot(status: Int) {
            binding.vStatusDot?.setBackgroundColor(
                when (status) {
                    1    -> COLOR_ONLINE
                    -1   -> COLOR_OFFLINE
                    else -> COLOR_UNKNOWN
                }
            )
        }

        private fun getCachedPingStatus(channelId: String): Int {
            val (status, ts) = pingCache[channelId] ?: return 0
            return if (System.currentTimeMillis() - ts < PING_TTL_MS) status else 0
        }

        private fun schedulePingIfNeeded(channel: Channel) {
            val cached = pingCache[channel.id]
            val cacheValid = cached != null &&
                System.currentTimeMillis() - cached.second < PING_TTL_MS
            if (cacheValid) return

            pingJob?.cancel()
            pingJob = lifecycleOwner.lifecycleScope.launch {
                val status = withContext(Dispatchers.IO) {
                    pingSemaphore.acquire()
                    try {
                        pingChannel(channel.url, channel.userAgent)
                    } finally {
                        pingSemaphore.release()
                    }
                }
                pingCache[channel.id] = Pair(status, System.currentTimeMillis())

                if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                    boundChannelId == channel.id
                ) {
                    updateStatusDot(status)
                }
            }
        }

        private fun pingChannel(url: String, userAgent: String): Int {
            return try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .apply {
                        if (userAgent.isNotEmpty()) header("User-Agent", userAgent)
                        else header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    }
                    .build()

                pingHttpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..403) 1 else -1
                }
            } catch (e: Exception) {
                -1
            }
        }
    }
}
