package com.orbixtv.app.ui.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.bumptech.glide.Glide
import com.orbixtv.app.R
import com.orbixtv.app.data.M3uParser
import com.orbixtv.app.databinding.ActivityPlayerBinding
import com.orbixtv.app.ui.MainViewModel

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_URL  = "channel_url"
        const val EXTRA_CHANNEL_LOGO = "channel_logo"
        const val EXTRA_USER_AGENT   = "user_agent"
        const val EXTRA_LICENSE_TYPE = "license_type"
        const val EXTRA_LICENSE_KEY  = "license_key"
        const val EXTRA_REFERER      = "referer"
        const val EXTRA_CHANNEL_ID   = "channel_id"
        private const val TAG = "PlayerActivity"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var viewModel: MainViewModel

    private var channelName = ""
    private var channelUrl  = ""
    private var channelLogo = ""
    private var userAgent   = ""
    private var licenseType = ""
    private var licenseKey  = ""
    private var referer     = ""
    private var channelId   = ""

    // Sleep timer
    private var sleepTimer: CountDownTimer? = null
    private var sleepTimerMinutesLeft = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()

        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        channelUrl  = intent.getStringExtra(EXTRA_CHANNEL_URL)  ?: ""
        channelLogo = intent.getStringExtra(EXTRA_CHANNEL_LOGO) ?: ""
        userAgent   = intent.getStringExtra(EXTRA_USER_AGENT)   ?: ""
        licenseType = intent.getStringExtra(EXTRA_LICENSE_TYPE) ?: ""
        licenseKey  = intent.getStringExtra(EXTRA_LICENSE_KEY)  ?: ""
        referer     = intent.getStringExtra(EXTRA_REFERER)      ?: ""
        channelId   = intent.getStringExtra(EXTRA_CHANNEL_ID)   ?: ""

        setupUI()
        setupPlayer()
    }

    private fun setupUI() {
        binding.tvChannelName.text = channelName

        if (channelLogo.isNotEmpty()) {
            Glide.with(this)
                .load(channelLogo)
                .placeholder(R.drawable.ic_tv_placeholder)
                .error(R.drawable.ic_tv_placeholder)
                .into(binding.ivChannelLogo)
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.btnFavorite.setOnClickListener {
            if (channelId.isNotEmpty()) {
                viewModel.toggleFavorite(channelId)
                updateFavoriteIcon()
            }
        }

        // ✅ FITUR: Tombol Sleep Timer
        binding.btnSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }

        // ✅ FITUR: Tombol PiP
        binding.btnPip.setOnClickListener {
            enterPipMode()
        }

        updateFavoriteIcon()
    }

    private fun updateFavoriteIcon() {
        if (channelId.isNotEmpty() && viewModel.isFavorite(channelId)) {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite_filled)
        } else {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite_border)
        }
    }

    // ========================
    // Player Setup
    // ========================

    private fun setupPlayer() {
        if (channelUrl.isEmpty()) {
            showError("URL stream tidak valid")
            return
        }

        showLoading(true)
        showError(null)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent(userAgent.ifEmpty {
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/90.0 Mobile Safari/537.36"
            })

            val headers = mutableMapOf<String, String>()
            if (referer.isNotEmpty()) {
                headers["Referer"] = referer
                val refererUri = Uri.parse(referer)
                val port = refererUri.port
                headers["Origin"] = buildString {
                    append(refererUri.scheme ?: "https")
                    append("://")
                    append(refererUri.host ?: "")
                    if (port != -1) append(":$port")
                }
            }
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
            setConnectTimeoutMs(15_000)
            setReadTimeoutMs(15_000)
        }

        val mediaSource = buildMediaSource(channelUrl, httpDataSourceFactory)

        // ✅ PATCH: Pastikan player sebelumnya sudah null sebelum buat baru
        player?.release()
        player = null

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY     -> { showLoading(false); showError(null) }
                        Player.STATE_BUFFERING -> showLoading(true)
                        Player.STATE_ENDED     -> showError("Stream berakhir")
                        Player.STATE_IDLE      -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Player error: ${error.message}")
                    showLoading(false)
                    showError("Gagal memuat: ${error.localizedMessage ?: "Error tidak diketahui"}")
                }
            })

            exo.setMediaSource(mediaSource)
            exo.playWhenReady = true
            exo.prepare()
        }
    }

    // ✅ PATCH: Deteksi stream lebih akurat via M3uParser.detectStreamType()
    private fun buildMediaSource(
        url: String,
        dataSourceFactory: DefaultHttpDataSource.Factory
    ): MediaSource {
        val uri = Uri.parse(url)
        return when (M3uParser.detectStreamType(url)) {
            M3uParser.StreamType.DASH -> {
                if (licenseType.isNotEmpty() && licenseKey.isNotEmpty()) {
                    buildDashWithClearKey(uri, dataSourceFactory)
                } else {
                    DashMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
                }
            }
            M3uParser.StreamType.HLS -> {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
            }
            else -> {
                DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
            }
        }
    }

    private fun buildDashWithClearKey(
        uri: Uri,
        dataSourceFactory: DefaultHttpDataSource.Factory
    ): MediaSource {
        return try {
            val keySets = parseClearKeys(licenseKey)
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                        .setLicenseUri("data:text/plain;base64," + buildClearKeyJson(keySets))
                        .build()
                )
                .build()
            DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } catch (e: Exception) {
            Log.w(TAG, "DRM setup failed, trying without DRM: ${e.message}")
            DashMediaSource.Factory(dataSourceFactory).createMediaSource(
                MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD).build()
            )
        }
    }

    private fun parseClearKeys(licenseKey: String): List<Pair<String, String>> {
        return licenseKey.split(",").mapNotNull { pair ->
            val parts = pair.trim().split(":")
            if (parts.size == 2) Pair(parts[0].trim(), parts[1].trim()) else null
        }
    }

    private fun buildClearKeyJson(keys: List<Pair<String, String>>): String {
        val keyArray = keys.joinToString(",") { (kid, k) ->
            """{"kty":"oct","kid":"${hexToBase64Url(kid)}","k":"${hexToBase64Url(k)}"}"""
        }
        val encoded = """{"keys":[$keyArray],"type":"temporary"}"""
        return android.util.Base64.encodeToString(encoded.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun hexToBase64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
        )
    }

    // ========================
    // ✅ FITUR: Picture-in-Picture
    // ========================

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } else {
            Toast.makeText(this, "PiP tidak didukung di Android ini", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter PiP saat user menekan tombol Home
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Sembunyikan UI overlay saat PiP aktif
        val uiVisibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        binding.topBar.visibility = uiVisibility
    }

    // ========================
    // ✅ FITUR: Sleep Timer
    // ========================

    private fun showSleepTimerDialog() {
        val options = arrayOf("15 menit", "30 menit", "45 menit", "60 menit", "90 menit", "Matikan Timer")
        val minutes = intArrayOf(15, 30, 45, 60, 90, 0)

        AlertDialog.Builder(this)
            .setTitle("⏱️  Sleep Timer")
            .setItems(options) { _, which ->
                val selected = minutes[which]
                if (selected == 0) {
                    cancelSleepTimer()
                } else {
                    startSleepTimer(selected)
                }
            }
            .show()
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerMinutesLeft = minutes
        updateSleepTimerButton(minutes)

        sleepTimer = object : CountDownTimer(minutes * 60_000L, 60_000L) {
            override fun onTick(millisUntilFinished: Long) {
                sleepTimerMinutesLeft = (millisUntilFinished / 60_000).toInt()
                updateSleepTimerButton(sleepTimerMinutesLeft)
            }

            override fun onFinish() {
                Toast.makeText(this@PlayerActivity, "Sleep timer selesai", Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()

        Toast.makeText(this, "Sleep timer: $minutes menit", Toast.LENGTH_SHORT).show()
    }

    private fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        sleepTimerMinutesLeft = 0
        binding.btnSleepTimer.setImageResource(R.drawable.ic_sleep_timer)
    }

    private fun updateSleepTimerButton(minutesLeft: Int) {
        binding.btnSleepTimer.setImageResource(R.drawable.ic_sleep_timer_active)
        binding.tvSleepTimer.text = "${minutesLeft}m"
        binding.tvSleepTimer.visibility = View.VISIBLE
    }

    // ========================
    // UI helpers
    // ========================

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String?) {
        if (message != null) {
            binding.errorLayout.visibility = View.VISIBLE
            binding.tvError.text = message
            binding.btnRetry.setOnClickListener {
                binding.errorLayout.visibility = View.GONE
                setupPlayer()
            }
        } else {
            binding.errorLayout.visibility = View.GONE
        }
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        // Jangan pause saat PiP aktif
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimer?.cancel()
        player?.release()
        player = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterFullscreen()
    }
}
