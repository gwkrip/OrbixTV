package com.orbixtv.app.ui.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
import com.orbixtv.app.data.Channel
import com.orbixtv.app.data.M3uParser
import com.orbixtv.app.databinding.ActivityPlayerBinding
import com.orbixtv.app.ui.MainViewModel
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME   = "channel_name"
        const val EXTRA_CHANNEL_URL    = "channel_url"
        const val EXTRA_CHANNEL_LOGO   = "channel_logo"
        const val EXTRA_USER_AGENT     = "user_agent"
        const val EXTRA_LICENSE_TYPE   = "license_type"
        const val EXTRA_LICENSE_KEY    = "license_key"
        const val EXTRA_REFERER        = "referer"
        const val EXTRA_CHANNEL_ID     = "channel_id"
        const val EXTRA_CHANNEL_INDEX  = "channel_index"
        // Hint MIME type dari atribut #EXTINF (type= / content-type=).
        // Dipakai untuk channel vnd yang URL-nya tidak mengandung pola HLS/DASH.
        const val EXTRA_MIME_TYPE_HINT = "mime_type_hint"
        private const val TAG = "PlayerActivity"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var viewModel: MainViewModel

    private var channelName  = ""
    private var channelUrl   = ""
    private var channelLogo  = ""
    private var userAgent    = ""
    private var licenseType  = ""
    private var licenseKey   = ""
    private var referer      = ""
    private var channelId    = ""
    private var mimeTypeHint = ""   // Hint vnd/mpegurl dari #EXTINF

    private var sleepTimer: CountDownTimer? = null
    private var sleepTimerMinutesLeft = 0

    private val hideUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideUiRunnable = Runnable { hideTopBar() }
    private val UI_HIDE_DELAY_MS = 4_000L

    private var allChannels: List<Channel> = emptyList()
    private var currentChannelIndex = -1

    private var retryCount = 0
    private val MAX_AUTO_RETRY = 2
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var preBufferPlayer: ExoPlayer? = null
    private var preBufferIndex: Int = -1

    private var isOrientationLocked = false

    private val gestureOverlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var gestureDetector: GestureDetector
    private var audioManager: AudioManager? = null
    private var initialVolume = 0
    private var maxVolume = 0
    private var initialBrightness = 0f
    private var gestureStartY = 0f
    private var gestureStartX = 0f
    private var isVolumeGesture = false

    private var wasPlayingBeforePause = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()

        channelName  = intent.getStringExtra(EXTRA_CHANNEL_NAME)   ?: ""
        channelUrl   = intent.getStringExtra(EXTRA_CHANNEL_URL)    ?: ""
        channelLogo  = intent.getStringExtra(EXTRA_CHANNEL_LOGO)   ?: ""
        userAgent    = intent.getStringExtra(EXTRA_USER_AGENT)     ?: ""
        licenseType  = intent.getStringExtra(EXTRA_LICENSE_TYPE)   ?: ""
        licenseKey   = intent.getStringExtra(EXTRA_LICENSE_KEY)    ?: ""
        referer      = intent.getStringExtra(EXTRA_REFERER)        ?: ""
        channelId    = intent.getStringExtra(EXTRA_CHANNEL_ID)     ?: ""
        mimeTypeHint = intent.getStringExtra(EXTRA_MIME_TYPE_HINT) ?: ""

        val intentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1)
        allChannels = viewModel.getAllChannels()
        currentChannelIndex = if (intentIndex >= 0) intentIndex
                              else allChannels.indexOfFirst { it.id == channelId }

        setupUI()
        setupPlayer()
        setupGestureDetector()
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

        binding.btnSleepTimer.setOnClickListener { showSleepTimerDialog() }
        binding.btnPip.setOnClickListener { enterPipMode() }

        binding.playerView.findViewById<android.widget.ImageButton?>(R.id.btn_prev_channel)
            ?.setOnClickListener { navigateChannel(-1) }
        binding.playerView.findViewById<android.widget.ImageButton?>(R.id.btn_next_channel)
            ?.setOnClickListener { navigateChannel(+1) }

        binding.btnLockOrientation?.setOnClickListener { toggleOrientationLock() }

        updateFavoriteIcon()
        updateLiveBadge()
    }

    private fun updateLiveBadge() {
        val isLive = currentChannelIndex >= 0 &&
            allChannels.isNotEmpty() &&
            allChannels[currentChannelIndex].streamType.let { it == "HLS" || it == "RTMP" }
        val liveBadge = binding.playerView.findViewById<android.widget.TextView?>(R.id.tv_live_badge)
        liveBadge?.visibility = if (isLive) View.VISIBLE else View.GONE
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

    /**
     * Siapkan & putar stream.
     *
     * Alur deteksi tipe stream:
     * 1. Cek mimeTypeHint dari atribut #EXTINF (type= / content-type=)
     * 2. Cek pola URL (ekstensi, path keyword)
     * 3. Jika masih PROGRESSIVE (ambigu) → HTTP HEAD sniff Content-Type
     * 4. Jika sniff juga gagal → DefaultMediaSourceFactory (ExoPlayer auto-detect)
     */
    private fun setupPlayer() {
        if (channelUrl.isEmpty()) {
            showError("URL stream tidak valid")
            return
        }

        retryHandler.removeCallbacksAndMessages(null)
        retryCount = 0

        showLoading(true)
        showError(null)

        // Tentukan stream type dari hint atau URL
        val detectedType = when {
            mimeTypeHint.isNotEmpty() -> M3uParser.mimeStringToStreamType(mimeTypeHint)
            else                      -> M3uParser.detectStreamType(channelUrl)
        }

        if (detectedType == M3uParser.StreamType.PROGRESSIVE) {
            // Tipe ambigu → sniff dulu via HTTP HEAD, baru build player
            lifecycleScope.launch {
                val sniffed = M3uParser.sniffContentType(channelUrl, userAgent, referer)
                Log.d(TAG, "Content-Type sniff result: $sniffed for $channelUrl")
                buildAndStartPlayer(sniffed ?: detectedType)
            }
        } else {
            buildAndStartPlayer(detectedType)
        }
    }

    private fun buildAndStartPlayer(streamType: M3uParser.StreamType) {
        val httpDataSourceFactory = buildDataSourceFactory()
        val mediaSource = buildMediaSource(channelUrl, streamType, httpDataSourceFactory)

        val exo = player ?: ExoPlayer.Builder(this).build().also { newExo ->
            player = newExo
            binding.playerView.player = newExo
            attachPlayerListener(newExo)
        }

        exo.stop()
        exo.setMediaSource(mediaSource)
        exo.playWhenReady = true
        exo.prepare()
    }

    private fun buildDataSourceFactory(): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory().apply {
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
    }

    private fun attachPlayerListener(exo: ExoPlayer) {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        showLoading(false)
                        showError(null)
                        retryCount = 0
                        preBufferHandler.removeCallbacksAndMessages(null)
                        preBufferHandler.postDelayed({ startPreBuffer() }, 1_500)
                    }
                    Player.STATE_BUFFERING -> showLoading(true)
                    Player.STATE_ENDED     -> showError("Stream berakhir")
                    Player.STATE_IDLE      -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}")
                showLoading(false)
                if (retryCount < MAX_AUTO_RETRY) {
                    retryCount++
                    Log.d(TAG, "Auto-retry $retryCount/$MAX_AUTO_RETRY")
                    retryHandler.postDelayed({
                        if (!isFinishing) setupPlayer()
                    }, 2_000L * retryCount)
                } else {
                    retryCount = 0
                    showError("Gagal memuat: ${error.localizedMessage ?: "Error tidak diketahui"}")
                }
            }
        })
    }

    /**
     * Bangun MediaSource yang tepat berdasarkan StreamType yang sudah diketahui.
     * Tidak ada lagi deteksi ulang di sini — type sudah final dari setupPlayer().
     */
    private fun buildMediaSource(
        url: String,
        streamType: M3uParser.StreamType,
        dataSourceFactory: DefaultHttpDataSource.Factory
    ): MediaSource {
        val uri = Uri.parse(url)
        return when (streamType) {
            M3uParser.StreamType.DASH -> {
                if (licenseType.isNotEmpty() && licenseKey.isNotEmpty()) {
                    buildDashWithClearKey(uri, dataSourceFactory)
                } else {
                    DashMediaSource.Factory(dataSourceFactory).createMediaSource(
                        MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD).build()
                    )
                }
            }
            M3uParser.StreamType.HLS -> {
                // Set MIME type eksplisit agar ExoPlayer tidak salah detect
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                    MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                )
            }
            else -> {
                // PROGRESSIVE / RTMP — biarkan ExoPlayer auto-detect dari respons
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
            if (keySets.isEmpty()) {
                Log.w(TAG, "No valid ClearKey pairs found, skipping DRM")
                return DashMediaSource.Factory(dataSourceFactory).createMediaSource(
                    MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD).build()
                )
            }
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                        .setLicenseUri("data:application/json;base64," + buildClearKeyJson(keySets))
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
            if (parts.size == 2) {
                val kid = parts[0].trim()
                val k   = parts[1].trim()
                if (isValidHex(kid) && isValidHex(k)) Pair(kid, k) else null
            } else null
        }
    }

    private fun isValidHex(s: String): Boolean =
        s.isNotEmpty() && s.length % 2 == 0 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

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
    // TV REMOTE: KeyEvent Handler
    // ========================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        showTopBarTemporarily()
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); showToastBrief("▶ Play"); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); showToastBrief("⏸ Pause"); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                player?.let {
                    if (it.isPlaying) { it.pause(); showToastBrief("⏸ Pause") }
                    else              { it.play();  showToastBrief("▶ Play")  }
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { player?.stop(); showToastBrief("⏹ Stop"); true }
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP    -> { navigateChannel(+1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN  -> { navigateChannel(-1); true }
            KeyEvent.KEYCODE_BACK       -> { finish(); true }
            KeyEvent.KEYCODE_MENU       -> { showSleepTimerDialog(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun navigateChannel(offset: Int) {
        if (allChannels.isEmpty()) {
            allChannels = viewModel.getAllChannels()
            currentChannelIndex = allChannels.indexOfFirst { it.id == channelId }
        }
        if (allChannels.isEmpty()) { showToastBrief("Daftar channel belum tersedia"); return }

        val nextIndex = when {
            currentChannelIndex < 0 -> 0
            else -> (currentChannelIndex + offset + allChannels.size) % allChannels.size
        }
        val nextChannel = allChannels[nextIndex]
        currentChannelIndex = nextIndex

        channelName  = nextChannel.name
        channelUrl   = nextChannel.url
        channelLogo  = nextChannel.logoUrl
        userAgent    = nextChannel.userAgent
        licenseType  = nextChannel.licenseType
        licenseKey   = nextChannel.licenseKey
        referer      = nextChannel.referer
        channelId    = nextChannel.id
        mimeTypeHint = nextChannel.mimeTypeHint   // Propagate hint ke channel berikutnya

        viewModel.onChannelWatched(channelId)

        binding.tvChannelName.text = channelName
        if (channelLogo.isNotEmpty()) {
            Glide.with(this).load(channelLogo)
                .placeholder(R.drawable.ic_tv_placeholder)
                .error(R.drawable.ic_tv_placeholder)
                .into(binding.ivChannelLogo)
        } else {
            binding.ivChannelLogo.setImageResource(R.drawable.ic_tv_placeholder)
        }
        updateFavoriteIcon()
        updateLiveBadge()

        val buffered = consumePreBuffer(nextIndex)
        if (buffered != null) {
            Log.d(TAG, "Using pre-buffered player for ${nextChannel.name}")
            player?.release()
            player = buffered
            binding.playerView.player = buffered
            attachPlayerListener(buffered)
            buffered.playWhenReady = true
            showLoading(false)
        } else {
            setupPlayer()
        }
        showTopBarTemporarily()
        showChannelPosition()
        showToastBrief("📺 $channelName")
    }

    private val hidePositionHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun showChannelPosition() {
        if (allChannels.isEmpty()) return
        val total = allChannels.size
        val pos = if (currentChannelIndex >= 0) currentChannelIndex + 1 else 1
        binding.tvChannelPosition.text = "$pos / $total"
        binding.tvChannelPosition.visibility = View.VISIBLE
        hidePositionHandler.removeCallbacksAndMessages(null)
        hidePositionHandler.postDelayed({ binding.tvChannelPosition.visibility = View.GONE }, 3_000)
    }

    // ========================
    // Pre-buffer channel berikutnya
    // ========================

    private val preBufferHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun startPreBuffer() {
        if (allChannels.isEmpty()) return
        val nextIndex = (currentChannelIndex + 1) % allChannels.size
        if (nextIndex == preBufferIndex) return

        releasePreBuffer()

        val nextChannel = allChannels[nextIndex]
        preBufferIndex = nextIndex

        try {
            val factory = androidx.media3.datasource.DefaultHttpDataSource.Factory().apply {
                setConnectTimeoutMs(10_000)
                setReadTimeoutMs(10_000)
                if (nextChannel.userAgent.isNotEmpty()) setUserAgent(nextChannel.userAgent)
            }

            val detectedType = when {
                nextChannel.mimeTypeHint.isNotEmpty() ->
                    M3uParser.mimeStringToStreamType(nextChannel.mimeTypeHint)
                else ->
                    M3uParser.detectStreamType(nextChannel.url)
            }

            val source = when (detectedType) {
                M3uParser.StreamType.DASH ->
                    androidx.media3.exoplayer.dash.DashMediaSource.Factory(factory)
                        .createMediaSource(androidx.media3.common.MediaItem.fromUri(nextChannel.url))
                M3uParser.StreamType.HLS ->
                    androidx.media3.exoplayer.hls.HlsMediaSource.Factory(factory)
                        .createMediaSource(
                            androidx.media3.common.MediaItem.Builder()
                                .setUri(nextChannel.url)
                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                .build()
                        )
                else ->
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(factory)
                        .createMediaSource(androidx.media3.common.MediaItem.fromUri(nextChannel.url))
            }

            preBufferPlayer = ExoPlayer.Builder(this).build().also { exo ->
                exo.setMediaSource(source)
                exo.playWhenReady = false
                exo.prepare()
            }
            Log.d(TAG, "Pre-buffering: ${nextChannel.name} [${detectedType.label}]")
        } catch (e: Exception) {
            Log.w(TAG, "Pre-buffer failed: ${e.message}")
            preBufferPlayer = null
            preBufferIndex = -1
        }
    }

    private fun consumePreBuffer(targetIndex: Int): ExoPlayer? {
        if (preBufferIndex != targetIndex) return null
        val exo = preBufferPlayer ?: return null
        preBufferPlayer = null
        preBufferIndex = -1
        return exo
    }

    private fun releasePreBuffer() {
        preBufferPlayer?.release()
        preBufferPlayer = null
        preBufferIndex = -1
    }

    private fun showToastBrief(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            enterPictureInPictureMode(params)
        } else {
            Toast.makeText(this, "PiP tidak didukung di Android ini", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val uiVisibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        binding.topBar.visibility = uiVisibility
    }

    // ========================
    // Sleep Timer
    // ========================

    private fun showSleepTimerDialog() {
        val options = arrayOf("15 menit", "30 menit", "45 menit", "60 menit", "90 menit", "Matikan Timer")
        val minutes = intArrayOf(15, 30, 45, 60, 90, 0)
        AlertDialog.Builder(this)
            .setTitle("⏱️  Sleep Timer")
            .setItems(options) { _, which ->
                val selected = minutes[which]
                if (selected == 0) cancelSleepTimer() else startSleepTimer(selected)
            }
            .show()
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerMinutesLeft = minutes
        updateSleepTimerButton(minutes)
        viewModel.setSleepTimer(minutes)

        sleepTimer = object : CountDownTimer(minutes * 60_000L, 60_000L) {
            override fun onTick(millisUntilFinished: Long) {
                sleepTimerMinutesLeft = (millisUntilFinished / 60_000).toInt()
                updateSleepTimerButton(sleepTimerMinutesLeft)
            }
            override fun onFinish() {
                Toast.makeText(this@PlayerActivity, "Sleep timer selesai", Toast.LENGTH_SHORT).show()
                viewModel.clearSleepTimer()
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
        binding.tvSleepTimer.visibility = View.GONE
        viewModel.clearSleepTimer()
    }

    private fun updateSleepTimerButton(minutesLeft: Int) {
        binding.btnSleepTimer.setImageResource(R.drawable.ic_sleep_timer_active)
        binding.tvSleepTimer.text = "${minutesLeft}m"
        binding.tvSleepTimer.visibility = View.VISIBLE
    }

    // ========================
    // Auto-hide top bar
    // ========================

    private fun showTopBarTemporarily() {
        hideUiHandler.removeCallbacks(hideUiRunnable)
        binding.topBar.animate().cancel()
        binding.topBar.visibility = View.VISIBLE
        binding.topBar.alpha = 1f
        hideUiHandler.postDelayed(hideUiRunnable, UI_HIDE_DELAY_MS)
    }

    private fun hideTopBar() {
        binding.topBar.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction { binding.topBar.visibility = View.GONE }
            .start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                showTopBarTemporarily()
                gestureStartX = event.x
                gestureStartY = event.y
                isVolumeGesture = event.x > resources.displayMetrics.widthPixels / 2f
                audioManager?.let { initialVolume = it.getStreamVolume(AudioManager.STREAM_MUSIC) }
                initialBrightness = window.attributes.screenBrightness.let {
                    if (it < 0) 0.5f else it
                }
            }
            MotionEvent.ACTION_MOVE -> handleGestureMove(event)
        }
        return super.onTouchEvent(event)
    }

    // ========================
    // UI helpers
    // ========================

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String?) {
        if (message != null) {
            hideUiHandler.removeCallbacks(hideUiRunnable)
            binding.topBar.visibility = View.VISIBLE
            binding.topBar.alpha = 1f
            binding.errorLayout.visibility = View.VISIBLE
            binding.tvError.text = message
            binding.btnRetry.setOnClickListener {
                binding.errorLayout.visibility = View.GONE
                setupPlayer()
            }
            binding.btnErrorBack.setOnClickListener { finish() }
        } else {
            binding.errorLayout.visibility = View.GONE
            showTopBarTemporarily()
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
        if (wasPlayingBeforePause) player?.play()
        showTopBarTemporarily()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        wasPlayingBeforePause = player?.isPlaying == true
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideUiHandler.removeCallbacks(hideUiRunnable)
        hidePositionHandler.removeCallbacksAndMessages(null)
        preBufferHandler.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacksAndMessages(null)
        gestureOverlayHandler.removeCallbacksAndMessages(null)
        releasePreBuffer()
        sleepTimer?.cancel()
        player?.release()
        player = null
    }

    // ========================
    // Gesture: Volume & Brightness
    // ========================

    private fun setupGestureDetector() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
            })
    }

    private fun handleGestureMove(event: MotionEvent) {
        val deltaY = gestureStartY - event.y
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        val ratio = deltaY / screenHeight

        if (isVolumeGesture) {
            val newVol = (initialVolume + ratio * maxVolume).toInt().coerceIn(0, maxVolume)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
            showGestureOverlay("🔊 ${(newVol * 100f / maxVolume).toInt()}%")
        } else {
            val newBright = (initialBrightness + ratio).coerceIn(0.01f, 1f)
            window.attributes = window.attributes.also { it.screenBrightness = newBright }
            showGestureOverlay("☀ ${(newBright * 100).toInt()}%")
        }
    }

    private fun showGestureOverlay(text: String) {
        binding.tvGestureOverlay?.let { tv ->
            tv.text = text
            tv.visibility = View.VISIBLE
            tv.animate().cancel()
            tv.alpha = 1f
            gestureOverlayHandler.removeCallbacksAndMessages(null)
            gestureOverlayHandler.postDelayed({
                tv.animate().alpha(0f).setDuration(300)
                    .withEndAction { tv.visibility = View.GONE }.start()
            }, 800)
        }
    }

    // ========================
    // Lock Orientasi
    // ========================

    private fun toggleOrientationLock() {
        isOrientationLocked = !isOrientationLocked
        requestedOrientation = if (isOrientationLocked)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val icon = if (isOrientationLocked) R.drawable.ic_screen_lock_landscape else R.drawable.ic_fullscreen
        binding.btnLockOrientation?.setImageResource(icon)
        showToastBrief(if (isOrientationLocked) "🔒 Landscape terkunci" else "🔓 Rotasi otomatis")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterFullscreen()
    }
}
