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

// ── BUG A FIX: ConcurrentHashMap agar aman diakses dari main thread maupun
// Dispatchers.IO tanpa ConcurrentModificationException.
private val pingCache = ConcurrentHashMap<String, Pair<Int, Long>>()
private const val PING_TTL_MS = 2 * 60 * 1000L

// ── BUG D FIX: Batasi maksimal 6 ping berjalan sekaligus.
private val pingSemaphore = Semaphore(6)

// OkHttpClient bersama untuk semua ping — dibuat sekali, bukan per-request.
// Membuat OkHttpClient baru setiap ping itu mahal: thread pool, connection pool,
// dan cache semuanya harus diinisialisasi ulang.
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

        private val cardView: CardView? = binding.root as? CardView
        private var pingJob: Job? = null
        // Simpan ID channel yang sedang di-bind agar coroutine tidak update slot yang salah
        private var boundChannelId: String = ""

        fun bind(channel: Channel) {
            boundChannelId = channel.id

            binding.tvChannelName.text = channel.name
            binding.tvGroupName.text   = channel.group
            binding.tvStreamType.text  = channel.streamType

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
                    // BUG D FIX: Tunggu slot semaphore sebelum ping
                    pingSemaphore.acquire()
                    try {
                        pingChannel(channel.url, channel.userAgent)
                    } finally {
                        pingSemaphore.release()
                    }
                }
                // BUG A FIX: ConcurrentHashMap — aman dari thread mana pun
                pingCache[channel.id] = Pair(status, System.currentTimeMillis())

                // BUG B FIX: Gunakan boundChannelId bukan NO_ID untuk cek
                // apakah ViewHolder ini masih terikat ke channel yang sama.
                // NO_POSITION (-1) != NO_ID.toInt() (-1) → keduanya -1, tapi
                // boundChannelId check jauh lebih eksplisit dan tidak ambigu.
                if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                    boundChannelId == channel.id
                ) {
                    updateStatusDot(status)
                }
            }
        }

        /**
         * BUG C FIX: Ganti HttpURLConnection.HEAD dengan OkHttp GET.
         * Pakai [pingHttpClient] yang dibuat sekali — bukan per-request.
         *
         * Alasan:
         * – Banyak server IPTV return 405 untuk HEAD → channel valid ditandai offline
         * – HttpURLConnection tidak mengikuti redirect HTTPS→HTTP
         * – GET + cancel setelah connect = ringan seperti HEAD, tapi lebih kompatibel
         */
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
                    // 2xx–403 = server hidup (403/401 = autentikasi butuh, tapi server jalan)
                    if (response.code in 200..403) 1 else -1
                }
            } catch (e: Exception) {
                -1
            }
        }
    }
}
