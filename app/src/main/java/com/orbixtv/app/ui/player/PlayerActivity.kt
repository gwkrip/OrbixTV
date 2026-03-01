package com.orbixtv.app.ui.player

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.bumptech.glide.Glide
import com.orbixtv.app.R
import com.orbixtv.app.databinding.ActivityPlayerBinding
import com.orbixtv.app.ui.MainViewModel
import java.util.UUID

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_URL = "channel_url"
        const val EXTRA_CHANNEL_LOGO = "channel_logo"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_LICENSE_TYPE = "license_type"
        const val EXTRA_LICENSE_KEY = "license_key"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_CHANNEL_ID = "channel_id"

        private const val TAG = "PlayerActivity"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var viewModel: MainViewModel

    private var channelName = ""
    private var channelUrl = ""
    private var channelLogo = ""
    private var userAgent = ""
    private var licenseType = ""
    private var licenseKey = ""
    private var referer = ""
    private var channelId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()

        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        channelUrl = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: ""
        channelLogo = intent.getStringExtra(EXTRA_CHANNEL_LOGO) ?: ""
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT) ?: ""
        licenseType = intent.getStringExtra(EXTRA_LICENSE_TYPE) ?: ""
        licenseKey = intent.getStringExtra(EXTRA_LICENSE_KEY) ?: ""
        referer = intent.getStringExtra(EXTRA_REFERER) ?: ""
        channelId = intent.getIntExtra(EXTRA_CHANNEL_ID, -1)

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
            if (channelId != -1) {
                viewModel.toggleFavorite(channelId)
                updateFavoriteIcon()
            }
        }

        updateFavoriteIcon()
    }

    private fun updateFavoriteIcon() {
        if (channelId != -1 && viewModel.isFavorite(channelId)) {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite_filled)
        } else {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite_border)
        }
    }

    private fun setupPlayer() {
        if (channelUrl.isEmpty()) {
            showError("URL stream tidak valid")
            return
        }

        showLoading(true)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            val ua = userAgent.ifEmpty {
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/90.0 Mobile Safari/537.36"
            }
            setUserAgent(ua)

            val headers = mutableMapOf<String, String>()
            if (referer.isNotEmpty()) {
                headers["Referer"] = referer
                // Derive Origin properly: scheme + host (+ port if non-default)
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

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            showLoading(false)
                            showError(null)
                        }
                        Player.STATE_BUFFERING -> showLoading(true)
                        Player.STATE_ENDED -> showError("Stream berakhir")
                        Player.STATE_IDLE -> {}
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

    private fun buildMediaSource(
        url: String,
        dataSourceFactory: DefaultHttpDataSource.Factory
    ): MediaSource {
        val uri = Uri.parse(url)

        return when {
            url.contains(".mpd") || url.contains("manifest.mpd") -> {
                if (licenseType.isNotEmpty() && licenseKey.isNotEmpty()) {
                    buildDashWithClearKey(uri, dataSourceFactory)
                } else {
                    DashMediaSource.Factory(dataSourceFactory).createMediaSource(
                        MediaItem.fromUri(uri)
                    )
                }
            }
            url.contains(".m3u8") || url.contains("playlist") || url.contains("chunklist") -> {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                    MediaItem.fromUri(uri)
                )
            }
            else -> {
                DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(
                    MediaItem.fromUri(uri)
                )
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
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String?) {
        if (message != null) {
            binding.errorLayout.visibility = View.VISIBLE
            binding.tvError.text = message
            binding.btnRetry.setOnClickListener {
                binding.errorLayout.visibility = View.GONE
                player?.release()
                player = null
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
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterFullscreen()
    }
}
