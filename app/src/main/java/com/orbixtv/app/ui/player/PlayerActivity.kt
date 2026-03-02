package com.orbixtv.app.ui.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
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
import com.orbixtv.app.data.M3uParser.StreamType
import com.orbixtv.app.databinding.ActivityPlayerBinding
import com.orbixtv.app.ui.MainViewModel

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
        const val EXTRA_MIME_TYPE_HINT = "mime_type_hint"
        private const val TAG = "PlayerActivity"

        // Berapa lama (ms) ExoPlayer boleh stuck di STATE_BUFFERING sebelum
        // kita paksa pindah ke format berikutnya.
        // 12 detik cukup untuk jaringan lambat, tidak terlalu lama untuk UX.
        private const val BUFFERING_TIMEOUT_MS = 12_000L

        // Timeout lebih lama untuk stream yang sudah terdeteksi jenisnya
        // (HLS pasti / DASH pasti) — beri waktu lebih karena kita yakin formatnya.
        private const val BUFFERING_TIMEOUT_KNOWN_MS = 20_000L

        // [FIX #4] Timeout khusus DRM — Widevine license round-trip bisa >20 detik
        // di jaringan lambat (download manifest → parse PSSH → request license → decrypt).
        private const val BUFFERING_TIMEOUT_DRM_MS = 35_000L
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
    private var mimeTypeHint = ""

    private var sleepTimer: CountDownTimer? = null
    private var sleepTimerMinutesLeft = 0

    private val hideUiHandler    = Handler(Looper.getMainLooper())
    private val hideUiRunnable   = Runnable { hideTopBar() }
    private val UI_HIDE_DELAY_MS = 4_000L

    private var allChannels: List<Channel> = emptyList()
    private var currentChannelIndex = -1

    // ── Sistem fallback bertingkat ──────────────────────────────────────────
    //
    // MASALAH ROOT CAUSE:
    // Channel vnd (Xtream Codes format: host:8080/live/user/pass/id.ts, dll)
    // tidak memiliki ekstensi URL dan server sering mengembalikan Content-Type
    // yang menyesatkan. ExoPlayer memilih format yang salah → masuk
    // STATE_BUFFERING selamanya karena data masuk tapi tidak bisa di-parse.
    // onPlayerError TIDAK dipanggil dalam kondisi ini → fallback tidak berjalan.
    //
    // SOLUSI: Buffering timeout yang memaksa pindah stage jika ExoPlayer
    // stuck di STATE_BUFFERING melebihi BUFFERING_TIMEOUT_MS.
    //
    // Urutan fallback untuk stream PROGRESSIVE (ambigu):
    //   Stage 0 → HLS tanpa MIME eksplisit (ExoPlayer sniff dari byte stream)
    //   Stage 1 → Default / auto-detect penuh
    //   Stage 2 → DASH
    // ────────────────────────────────────────────────────────────────────────
    private enum class FallbackStage { HLS, DEFAULT, DASH }
    private var fallbackStage   = FallbackStage.HLS
    private var isUsingFallback = false

    // Handler khusus untuk buffering timeout
    private val bufferingTimeoutHandler = Handler(Looper.getMainLooper())
    private val bufferingTimeoutRunnable = Runnable { onBufferingTimeout() }

    private var retryCount = 0
    private val MAX_AUTO_RETRY = 2
    private val retryHandler = Handler(Looper.getMainLooper())

    private var preBufferPlayer: ExoPlayer? = null
    private var preBufferIndex  = -1

    private var isOrientationLocked  = false
    private var wasPlayingBeforePause = true

    private val gestureOverlayHandler = Handler(Looper.getMainLooper())
    private lateinit var gestureDetector: GestureDetector
    private var audioManager: AudioManager? = null
    private var initialVolume    = 0
    private var maxVolume        = 0
    private var initialBrightness = 0f
    private var gestureStartY    = 0f
    private var gestureStartX    = 0f
    private var isVolumeGesture  = false

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

    // ════════════════════════════════════════════════════════════════════════
    // SETUP PLAYER — Entry point utama
    // ════════════════════════════════════════════════════════════════════════

    private fun setupPlayer() {
        if (channelUrl.isEmpty()) { showError("URL stream tidak valid"); return }

        // Bersihkan semua handler yang mungkin masih berjalan dari sesi sebelumnya
        retryHandler.removeCallbacksAndMessages(null)
        cancelBufferingTimeout()
        // [FIX BUG-A] JANGAN reset retryCount di sini!
        // Jika direset di sini, onPlayerError/onBufferingTimeout selalu melihat
        // retryCount=0 → kondisi else (showError) tidak pernah tercapai
        // → infinite retry loop → loading selamanya.
        // retryCount HANYA boleh direset di:
        //   1. STATE_READY (stream berhasil diputar)
        //   2. navigateChannel (pindah channel baru)
        //   3. Tombol "Coba Lagi" di error layout (user-initiated)

        showLoading(true)
        showError(null)

        val detectedType = resolveStreamType()
        Log.d(TAG, "resolveStreamType=$detectedType hint='$mimeTypeHint' url=$channelUrl")

        isUsingFallback = (detectedType == M3uParser.StreamType.PROGRESSIVE)
        fallbackStage   = FallbackStage.HLS

        if (isUsingFallback) {
            // Mulai dari HLS (format paling umum di IPTV)
            startWithFallbackStage(FallbackStage.HLS)
        } else {
            startWithKnownType(detectedType)
        }
    }

    /**
     * Tentukan tipe stream dari metadata saja (tanpa I/O / HEAD request).
     * HEAD request tidak reliable untuk server IPTV:
     * – Banyak server reply Content-Type: text/html meski isi-nya HLS
     * – Banyak server tidak support HEAD → 405 Method Not Allowed
     */
    private fun resolveStreamType(): M3uParser.StreamType {
        if (mimeTypeHint.isNotEmpty()) {
            val t = M3uParser.mimeStringToStreamType(mimeTypeHint)
            if (t != M3uParser.StreamType.PROGRESSIVE) return t
        }
        return M3uParser.detectStreamType(channelUrl)
    }

    // ── Start dengan tipe yang sudah DIKETAHUI PASTI ─────────────────────

    private fun startWithKnownType(type: M3uParser.StreamType) {
        Log.d(TAG, "startWithKnownType: $type hasDRM=${licenseKey.isNotEmpty()}")
        val factory = buildDataSourceFactory()
        val source  = buildMediaSourceForKnownType(channelUrl, type, factory)
        // [FIX #4] Gunakan timeout lebih panjang jika ada DRM (Widevine license round-trip)
        launchExoPlayer(source, isKnownType = true, hasDrm = licenseKey.isNotEmpty())
    }

    // ── Start dengan fallback bertingkat ─────────────────────────────────

    private fun startWithFallbackStage(stage: FallbackStage) {
        fallbackStage = stage
        Log.d(TAG, "startWithFallbackStage: $stage")

        val factory = buildDataSourceFactory()
        val uri     = Uri.parse(channelUrl)

        val source: MediaSource = when (stage) {
            FallbackStage.HLS -> {
                // KUNCI: tanpa setMimeType() → ExoPlayer sniff dari byte pertama isi.
                // Ini yang handle vnd.apple.mpegurl, x-mpegurl, .ts stream, dll.
                HlsMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            FallbackStage.DEFAULT -> {
                // ExoPlayer full auto-detect dari content bytes
                DefaultMediaSourceFactory(factory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            FallbackStage.DASH -> {
                // [FIX #2] Terapkan DRM juga di fallback DASH — URL ambigu (tanpa .mpd)
                // sangat umum di playlist IPTV. Sebelumnya DRM diabaikan di sini.
                val drmSource = tryBuildDrmMediaSource(uri, factory)
                drmSource ?: DashMediaSource.Factory(factory).createMediaSource(
                    MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD).build()
                )
            }
        }
        launchExoPlayer(source, isKnownType = false, hasDrm = licenseKey.isNotEmpty())
    }

    /**
     * Launch ExoPlayer dengan MediaSource yang diberikan.
     *
     * [FIX #1] ExoPlayer selalu di-release dan dibuat ulang jika ada DRM (licenseKey tidak kosong).
     * Root cause: DRM session yang gagal/timeout tidak bisa di-reset hanya dengan stop() +
     * clearMediaItems(). Player baru diperlukan agar Widevine/PlayReady/ClearKey
     * memulai license negotiation dari awal tanpa state korup dari sesi sebelumnya.
     *
     * [FIX #4] [hasDrm] menentukan timeout buffering: DRM membutuhkan waktu lebih
     * panjang karena ada round-trip ke license server sebelum frame pertama bisa didekripsi.
     */
    private fun launchExoPlayer(source: MediaSource, isKnownType: Boolean, hasDrm: Boolean = false) {
        // [FIX #1] Jika ada DRM, selalu buat player baru — DRM session tidak bisa di-reset.
        // Jika tidak ada DRM, reuse player yang ada untuk efisiensi (menghindari black-flash).
        if (hasDrm && player != null) {
            Log.d(TAG, "DRM mode: releasing existing player to reset DRM session")
            player!!.release()
            player = null
        }

        val exo = player ?: ExoPlayer.Builder(this).build().also { newExo ->
            player = newExo
            binding.playerView.player = newExo
            attachPlayerListener(newExo)
        }
        exo.stop()
        exo.clearMediaItems()
        exo.setMediaSource(source)
        exo.playWhenReady = true
        exo.prepare()

        // [FIX #4] Timeout bertingkat: DRM > known-type > fallback
        val timeout = when {
            hasDrm      -> BUFFERING_TIMEOUT_DRM_MS
            isKnownType -> BUFFERING_TIMEOUT_KNOWN_MS
            else        -> BUFFERING_TIMEOUT_MS
        }
        scheduleBufferingTimeout(timeout)
    }

    // ── Buffering Timeout ─────────────────────────────────────────────────

    private fun scheduleBufferingTimeout(delayMs: Long) {
        cancelBufferingTimeout()
        bufferingTimeoutHandler.postDelayed(bufferingTimeoutRunnable, delayMs)
        Log.d(TAG, "Buffering timeout scheduled: ${delayMs}ms [fallback=$isUsingFallback stage=$fallbackStage]")
    }

    private fun cancelBufferingTimeout() {
        bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutRunnable)
    }

    /**
     * Dipanggil ketika ExoPlayer stuck di STATE_BUFFERING melebihi timeout.
     * Ini adalah satu-satunya cara menangani kasus loading tanpa henti karena
     * onPlayerError tidak dipanggil saat ExoPlayer berhasil connect tapi
     * tidak bisa parse format stream.
     */
    private fun onBufferingTimeout() {
        val state = player?.playbackState
        // Hanya bertindak jika memang masih buffering
        if (state != Player.STATE_BUFFERING && state != Player.STATE_IDLE) {
            Log.d(TAG, "Buffering timeout fired but state=$state, ignoring")
            return
        }

        Log.w(TAG, "Buffering timeout! fallback=$isUsingFallback stage=$fallbackStage")

        if (isUsingFallback) {
            advanceFallbackStage()
        } else {
            // Stream dengan tipe diketahui tapi timeout → coba lagi sekali,
            // lalu tampilkan error
            if (retryCount < MAX_AUTO_RETRY) {
                retryCount++
                Log.d(TAG, "Known-type buffering timeout, retry $retryCount/$MAX_AUTO_RETRY")
                retryHandler.postDelayed({ if (!isFinishing) setupPlayer() }, 1_000L)
            } else {
                retryCount = 0
                showLoading(false)
                showError("Stream tidak merespons. Periksa koneksi atau coba lagi.")
            }
        }
    }

    /** Pindah ke stage fallback berikutnya, atau tampilkan error jika sudah habis. */
    private fun advanceFallbackStage() {
        val nextStage: FallbackStage? = when (fallbackStage) {
            FallbackStage.HLS     -> FallbackStage.DEFAULT
            FallbackStage.DEFAULT -> FallbackStage.DASH
            FallbackStage.DASH    -> null
        }

        if (nextStage != null) {
            Log.d(TAG, "Advancing fallback: $fallbackStage → $nextStage")
            retryHandler.postDelayed({
                if (!isFinishing) startWithFallbackStage(nextStage)
            }, 300L)
        } else {
            // Semua format dicoba, semua timeout
            isUsingFallback = false
            showLoading(false)
            showError("Tidak dapat memutar channel ini. Format stream tidak dikenali atau server tidak merespons.")
        }
    }

    // ── Listener ExoPlayer ────────────────────────────────────────────────

    private fun attachPlayerListener(exo: ExoPlayer) {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        // Berhasil! Batalkan semua timeout & reset state
                        cancelBufferingTimeout()
                        retryHandler.removeCallbacksAndMessages(null)
                        isUsingFallback = false
                        retryCount = 0
                        showLoading(false)
                        showError(null)
                        preBufferHandler.removeCallbacksAndMessages(null)
                        preBufferHandler.postDelayed({ startPreBuffer() }, 1_500)
                        Log.d(TAG, "STATE_READY — stream playing")
                    }
                    Player.STATE_BUFFERING -> {
                        showLoading(true)
                        // Timeout sudah dijadwalkan di launchExoPlayer(),
                        // tidak perlu reschedule di sini.
                    }
                    Player.STATE_ENDED -> {
                        cancelBufferingTimeout()
                        showError("Stream berakhir")
                    }
                    Player.STATE_IDLE -> cancelBufferingTimeout()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                cancelBufferingTimeout()
                Log.e(TAG, "onPlayerError [fallback=$isUsingFallback stage=$fallbackStage]: ${error.message}")
                showLoading(false)

                if (isUsingFallback) {
                    // Error eksplisit → langsung lanjut ke stage berikutnya tanpa delay
                    advanceFallbackStage()
                    return
                }

                // Stream tipe diketahui → auto-retry biasa
                if (retryCount < MAX_AUTO_RETRY) {
                    retryCount++
                    retryHandler.postDelayed({ if (!isFinishing) setupPlayer() }, 2_000L * retryCount)
                } else {
                    retryCount = 0
                    showError("Gagal memuat: ${error.localizedMessage ?: "Error tidak diketahui"}")
                }
            }
        })
    }

    // ── Build helpers ─────────────────────────────────────────────────────

    private fun buildDataSourceFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory().apply {
            setUserAgent(userAgent.ifEmpty {
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/90.0 Mobile Safari/537.36"
            })
            val headers = mutableMapOf<String, String>()
            if (referer.isNotEmpty()) {
                headers["Referer"] = referer
                val uri  = Uri.parse(referer)
                val port = uri.port
                headers["Origin"] = buildString {
                    append(uri.scheme ?: "https").append("://").append(uri.host ?: "")
                    if (port != -1) append(":$port")
                }
            }
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
            setConnectTimeoutMs(15_000)
            setReadTimeoutMs(15_000)
        }

    private fun buildMediaSourceForKnownType(
        url: String,
        type: M3uParser.StreamType,
        factory: DefaultHttpDataSource.Factory
    ): MediaSource {
        val uri = Uri.parse(url)
        return when (type) {
            M3uParser.StreamType.DASH -> {
                val drmSource = tryBuildDrmMediaSource(uri, factory)
                drmSource ?: DashMediaSource.Factory(factory).createMediaSource(
                    MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD).build()
                )
            }
            M3uParser.StreamType.HLS ->
                HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri))
            else ->
                DefaultMediaSourceFactory(factory).createMediaSource(MediaItem.fromUri(uri))
        }
    }

    // ── DRM routing ───────────────────────────────────────────────────────
    //
    // Bug sebelumnya:
    //  1. Semua licenseType diperlakukan sebagai ClearKey → Widevine/PlayReady gagal
    //  2. parseClearKeys() menolak UUID dengan dash (format paling umum di IPTV)
    //  3. Key dalam format base64url juga ditolak
    //
    // Routing yang benar:
    //  • clearkey / org.w3.clearkey  → C.CLEARKEY_UUID  + inline JSON
    //  • widevine / edef8ba9-...     → C.WIDEVINE_UUID  + license server URL
    //  • playready / 9a04f079-...    → C.PLAYREADY_UUID + license server URL
    //  • kosong / tidak dikenal      → tidak ada DRM (return null)
    // ─────────────────────────────────────────────────────────────────────

    private fun tryBuildDrmMediaSource(
        uri: Uri,
        factory: DefaultHttpDataSource.Factory
    ): MediaSource? {
        if (licenseKey.isEmpty()) return null

        val lt = licenseType.lowercase().trim()

        return try {
            when {
                // ── ClearKey ──────────────────────────────────────────────
                lt.isEmpty() || lt.contains("clearkey") || lt == "org.w3.clearkey" -> {
                    val keys = parseClearKeys(licenseKey)
                    if (keys.isEmpty()) {
                        Log.w(TAG, "ClearKey: no valid key pairs parsed from '$licenseKey'")
                        return null
                    }
                    val inlineJson = "data:application/json;base64," + buildClearKeyJson(keys)
                    Log.d(TAG, "ClearKey: ${keys.size} key pair(s), inline JSON ready")
                    DashMediaSource.Factory(factory).createMediaSource(
                        MediaItem.Builder()
                            .setUri(uri)
                            .setMimeType(MimeTypes.APPLICATION_MPD)
                            .setDrmConfiguration(
                                MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                                    .setLicenseUri(inlineJson)
                                    .build()
                            ).build()
                    )
                }

                // ── Widevine ──────────────────────────────────────────────
                lt.contains("widevine") ||
                lt == "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" -> {
                    // Format: "https://license.url" atau "https://license.url|Header: Value&Header2: Value2"
                    val parts          = licenseKey.split("|")
                    val licenseUrl     = parts[0].trim()
                    val requestHeaders = if (parts.size > 1) parseHeaderString(parts[1]) else emptyMap()
                    Log.d(TAG, "Widevine: url=$licenseUrl headers=${requestHeaders.keys}")
                    DashMediaSource.Factory(factory).createMediaSource(
                        MediaItem.Builder()
                            .setUri(uri)
                            .setMimeType(MimeTypes.APPLICATION_MPD)
                            .setDrmConfiguration(
                                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                    .setLicenseUri(licenseUrl)
                                    .setLicenseRequestHeaders(requestHeaders)
                                    .build()
                            ).build()
                    )
                }

                // ── PlayReady ─────────────────────────────────────────────
                lt.contains("playready") ||
                lt == "9a04f079-9840-4286-ab92-e65be0885f95" -> {
                    val parts          = licenseKey.split("|")
                    val licenseUrl     = parts[0].trim()
                    val requestHeaders = if (parts.size > 1) parseHeaderString(parts[1]) else emptyMap()
                    Log.d(TAG, "PlayReady: url=$licenseUrl")
                    DashMediaSource.Factory(factory).createMediaSource(
                        MediaItem.Builder()
                            .setUri(uri)
                            .setMimeType(MimeTypes.APPLICATION_MPD)
                            .setDrmConfiguration(
                                MediaItem.DrmConfiguration.Builder(C.PLAYREADY_UUID)
                                    .setLicenseUri(licenseUrl)
                                    .setLicenseRequestHeaders(requestHeaders)
                                    .build()
                            ).build()
                    )
                }

                else -> {
                    Log.w(TAG, "Unknown licenseType '$licenseType', skipping DRM")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DRM build failed: ${e.message}", e)
            null
        }
    }

    /**
     * Parse string header format "Key: Value&Key2: Value2" atau "Key=Value&Key2=Value2"
     * yang umum digunakan setelah pipe di licenseKey Widevine/PlayReady.
     */
    private fun parseHeaderString(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        raw.split("&").forEach { entry ->
            // Coba format "Key: Value" dulu, lalu "Key=Value"
            val sep = if (entry.contains(": ")) ": " else "="
            val idx = entry.indexOf(sep)
            if (idx > 0) {
                val k = entry.substring(0, idx).trim()
                val v = entry.substring(idx + sep.length).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) result[k] = v
            }
        }
        return result
    }

    /**
     * Parse ClearKey pairs dari string licenseKey.
     *
     * Format yang didukung:
     *  1. Hex plain:    "aaaabbbb...1111:ffffeeee...8888"
     *  2. UUID dash:    "aaaabbbb-cccc-dddd-1111-2222:ffffeeee-dddc-ccbb-1111-0000"
     *  3. Base64url:    "qqu7u8zM3d0RESLSM0RE:FO7u3czMu7ERAQCZ99iI"
     *  4. Multi-pair:   "kid1:k1,kid2:k2"
     *
     * Return: list of (kid_base64url, k_base64url) siap masuk JSON
     */
    private fun parseClearKeys(licenseKey: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (raw in licenseKey.split(",")) {
            val pair = raw.trim()
            // Pisah hanya pada ':' pertama agar URL (yang punya '://') tidak tersplit
            val colonIdx = pair.indexOf(':')
            if (colonIdx <= 0) continue
            val rawKid = pair.substring(0, colonIdx).trim()
            val rawK   = pair.substring(colonIdx + 1).trim()
            // Skip jika rawK terlihat seperti URL (Widevine/PlayReady format)
            if (rawK.startsWith("http://") || rawK.startsWith("https://")) continue

            val kidB64 = toBase64Url(rawKid) ?: continue
            val kB64   = toBase64Url(rawK)   ?: continue
            result.add(Pair(kidB64, kB64))
            Log.d(TAG, "ClearKey parsed: kid=${kidB64.take(8)}… k=${kB64.take(8)}…")
        }
        return result
    }

    /**
     * Konversi satu string key ke base64url.
     * Menerima: hex (dengan atau tanpa dash), base64url.
     * Return null jika format tidak dikenal.
     */
    private fun toBase64Url(raw: String): String? {
        val stripped = raw.replace("-", "").trim()

        // Coba hex dulu
        if (stripped.isNotEmpty() && stripped.length % 2 == 0 &&
            stripped.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        ) {
            return try {
                val bytes = ByteArray(stripped.length / 2) { i ->
                    stripped.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                android.util.Base64.encodeToString(
                    bytes,
                    android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
                )
            } catch (e: Exception) { null }
        }

        // Coba base64url — jika sudah dalam format itu, kembalikan apa adanya
        return try {
            val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
            android.util.Base64.decode(padded, android.util.Base64.URL_SAFE)
            raw   // valid base64url, pakai langsung
        } catch (e: Exception) { null }
    }

    private fun buildClearKeyJson(keys: List<Pair<String, String>>): String {
        // keys sudah berisi (kid_base64url, k_base64url) dari parseClearKeys()
        val arr = keys.joinToString(",") { (kid, k) ->
            """{"kty":"oct","kid":"$kid","k":"$k"}"""
        }
        return android.util.Base64.encodeToString(
            """{"keys":[$arr],"type":"temporary"}""".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // UI
    // ════════════════════════════════════════════════════════════════════════

    private fun setupUI() {
        binding.tvChannelName.text = channelName
        if (channelLogo.isNotEmpty()) Glide.with(this).load(channelLogo)
            .placeholder(R.drawable.ic_tv_placeholder).error(R.drawable.ic_tv_placeholder)
            .into(binding.ivChannelLogo)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnFavorite.setOnClickListener {
            if (channelId.isNotEmpty()) { viewModel.toggleFavorite(channelId); updateFavoriteIcon() }
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
        val live = currentChannelIndex >= 0 && allChannels.isNotEmpty() &&
            allChannels[currentChannelIndex].streamType.let { it == "HLS" || it == "RTMP" }
        binding.playerView.findViewById<android.widget.TextView?>(R.id.tv_live_badge)
            ?.visibility = if (live) View.VISIBLE else View.GONE
    }

    private fun updateFavoriteIcon() {
        binding.btnFavorite.setImageResource(
            if (channelId.isNotEmpty() && viewModel.isFavorite(channelId))
                R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
        )
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String?) {
        if (message != null) {
            hideUiHandler.removeCallbacks(hideUiRunnable)
            binding.topBar.visibility = View.VISIBLE; binding.topBar.alpha = 1f
            binding.errorLayout.visibility = View.VISIBLE
            binding.tvError.text = message
            binding.btnRetry.setOnClickListener {
                binding.errorLayout.visibility = View.GONE
                retryCount = 0  // [FIX BUG-A] User klik retry = mulai dari awal
                setupPlayer()
            }
            binding.btnErrorBack.setOnClickListener { finish() }
        } else {
            binding.errorLayout.visibility = View.GONE
            showTopBarTemporarily()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NAVIGASI CHANNEL
    // ════════════════════════════════════════════════════════════════════════

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        showTopBarTemporarily()
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY        -> { player?.play();  showToast("▶ Play");  true }
            KeyEvent.KEYCODE_MEDIA_PAUSE       -> { player?.pause(); showToast("⏸ Pause"); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER             -> {
                player?.let { if (it.isPlaying) { it.pause(); showToast("⏸ Pause") }
                              else              { it.play();  showToast("▶ Play")  } }; true
            }
            KeyEvent.KEYCODE_MEDIA_STOP        -> { player?.stop(); showToast("⏹ Stop"); true }
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP           -> { navigateChannel(+1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN         -> { navigateChannel(-1); true }
            KeyEvent.KEYCODE_BACK              -> { finish(); true }
            KeyEvent.KEYCODE_MENU              -> { showSleepTimerDialog(); true }
            else                               -> super.onKeyDown(keyCode, event)
        }
    }

    private fun navigateChannel(offset: Int) {
        if (allChannels.isEmpty()) {
            allChannels = viewModel.getAllChannels()
            currentChannelIndex = allChannels.indexOfFirst { it.id == channelId }
        }
        if (allChannels.isEmpty()) { showToast("Daftar channel belum tersedia"); return }

        val nextIndex = (currentChannelIndex.coerceAtLeast(0) + offset + allChannels.size) % allChannels.size
        val next = allChannels[nextIndex]
        currentChannelIndex = nextIndex

        channelName  = next.name;  channelUrl  = next.url;      channelLogo = next.logoUrl
        userAgent    = next.userAgent; licenseType = next.licenseType
        licenseKey   = next.licenseKey; referer  = next.referer
        channelId    = next.id;    mimeTypeHint = next.mimeTypeHint
        retryCount   = 0  // [FIX BUG-A] Channel baru = retry counter baru dari 0

        viewModel.onChannelWatched(channelId)
        binding.tvChannelName.text = channelName
        if (channelLogo.isNotEmpty()) Glide.with(this).load(channelLogo)
            .placeholder(R.drawable.ic_tv_placeholder).error(R.drawable.ic_tv_placeholder)
            .into(binding.ivChannelLogo)
        else binding.ivChannelLogo.setImageResource(R.drawable.ic_tv_placeholder)
        updateFavoriteIcon(); updateLiveBadge()

        val buffered = consumePreBuffer(nextIndex)
        if (buffered != null) {
            Log.d(TAG, "Pre-buffer hit: ${next.name}")
            player?.release()
            player = buffered
            binding.playerView.player = buffered
            attachPlayerListener(buffered)
            buffered.playWhenReady = true
            showLoading(false)
            cancelBufferingTimeout()
        } else {
            setupPlayer()
        }
        showTopBarTemporarily(); showChannelPosition(); showToast("📺 $channelName")
    }

    private val hidePositionHandler = Handler(Looper.getMainLooper())

    private fun showChannelPosition() {
        if (allChannels.isEmpty()) return
        binding.tvChannelPosition.text = "${currentChannelIndex + 1} / ${allChannels.size}"
        binding.tvChannelPosition.visibility = View.VISIBLE
        hidePositionHandler.removeCallbacksAndMessages(null)
        hidePositionHandler.postDelayed({ binding.tvChannelPosition.visibility = View.GONE }, 3_000)
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRE-BUFFER
    // ════════════════════════════════════════════════════════════════════════

    private val preBufferHandler = Handler(Looper.getMainLooper())

    private fun startPreBuffer() {
        if (allChannels.isEmpty()) return
        val nextIndex = (currentChannelIndex + 1) % allChannels.size
        if (nextIndex == preBufferIndex) return
        releasePreBuffer()
        val next = allChannels[nextIndex]

        // [FIX #3] Skip pre-buffer untuk channel DRM — license negotiation tidak bisa
        // dilakukan di background tanpa surface aktif, dan player DRM harus di-release
        // lalu dibuat ulang saat dimainkan (Fix #1). Pre-buffer channel DRM akan
        // menyebabkan resource waste dan state yang tidak konsisten.
        if (next.licenseKey.isNotEmpty()) {
            Log.d(TAG, "Pre-buffer skipped: '${next.name}' requires DRM license")
            return
        }

        preBufferIndex = nextIndex
        try {
            val factory = androidx.media3.datasource.DefaultHttpDataSource.Factory().apply {
                setConnectTimeoutMs(10_000); setReadTimeoutMs(10_000)
                if (next.userAgent.isNotEmpty()) setUserAgent(next.userAgent)
            }
            val uri = Uri.parse(next.url)

            // [FIX #3] Gunakan MediaSource yang sesuai dengan streamType channel berikutnya,
            // bukan selalu HLS. Pre-buffer HLS untuk channel DASH akan selalu gagal diam-diam
            // (consumePreBuffer() → null → setupPlayer() dari nol → jeda loading terlihat).
            val source: androidx.media3.exoplayer.source.MediaSource = when (next.streamType) {
                "DASH" -> androidx.media3.exoplayer.dash.DashMediaSource.Factory(factory)
                    .createMediaSource(
                        MediaItem.Builder()
                            .setUri(uri)
                            .setMimeType(MimeTypes.APPLICATION_MPD)
                            .build()
                    )
                else -> // HLS, PROGRESSIVE, RTMP → coba HLS sebagai default terbaik
                    HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri))
            }

            preBufferPlayer = ExoPlayer.Builder(this).build().also { exo ->
                exo.setMediaSource(source)
                exo.playWhenReady = false
                exo.prepare()
            }
            Log.d(TAG, "Pre-buffer started: '${next.name}' type=${next.streamType}")
        } catch (e: Exception) {
            Log.w(TAG, "Pre-buffer failed: ${e.message}")
            preBufferPlayer = null; preBufferIndex = -1
        }
    }

    private fun consumePreBuffer(idx: Int): ExoPlayer? {
        if (preBufferIndex != idx) return null
        val exo = preBufferPlayer ?: return null
        preBufferPlayer = null; preBufferIndex = -1
        return exo
    }

    private fun releasePreBuffer() {
        preBufferPlayer?.release(); preBufferPlayer = null; preBufferIndex = -1
    }

    // ════════════════════════════════════════════════════════════════════════
    // PiP
    // ════════════════════════════════════════════════════════════════════════

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        } else showToast("PiP tidak didukung di Android ini")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true)
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
    }

    override fun onPictureInPictureModeChanged(pip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(pip, cfg)
        binding.topBar.visibility = if (pip) View.GONE else View.VISIBLE
    }

    // ════════════════════════════════════════════════════════════════════════
    // SLEEP TIMER
    // ════════════════════════════════════════════════════════════════════════

    private fun showSleepTimerDialog() {
        val opts = arrayOf("15 menit","30 menit","45 menit","60 menit","90 menit","Matikan Timer")
        val mins = intArrayOf(15, 30, 45, 60, 90, 0)
        AlertDialog.Builder(this).setTitle("⏱️  Sleep Timer")
            .setItems(opts) { _, i -> if (mins[i] == 0) cancelSleepTimer() else startSleepTimer(mins[i]) }
            .show()
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerMinutesLeft = minutes
        updateSleepTimerButton(minutes)
        viewModel.setSleepTimer(minutes)
        sleepTimer = object : CountDownTimer(minutes * 60_000L, 60_000L) {
            override fun onTick(ms: Long) { sleepTimerMinutesLeft = (ms / 60_000).toInt(); updateSleepTimerButton(sleepTimerMinutesLeft) }
            override fun onFinish() { showToast("Sleep timer selesai"); viewModel.clearSleepTimer(); finish() }
        }.start()
        showToast("Sleep timer: $minutes menit")
    }

    private fun cancelSleepTimer() {
        sleepTimer?.cancel(); sleepTimer = null; sleepTimerMinutesLeft = 0
        binding.btnSleepTimer.setImageResource(R.drawable.ic_sleep_timer)
        binding.tvSleepTimer.visibility = View.GONE
        viewModel.clearSleepTimer()
    }

    private fun updateSleepTimerButton(min: Int) {
        binding.btnSleepTimer.setImageResource(R.drawable.ic_sleep_timer_active)
        binding.tvSleepTimer.text = "${min}m"; binding.tvSleepTimer.visibility = View.VISIBLE
    }

    // ════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private fun showTopBarTemporarily() {
        hideUiHandler.removeCallbacks(hideUiRunnable)
        binding.topBar.animate().cancel()
        binding.topBar.visibility = View.VISIBLE; binding.topBar.alpha = 1f
        hideUiHandler.postDelayed(hideUiRunnable, UI_HIDE_DELAY_MS)
    }

    private fun hideTopBar() {
        binding.topBar.animate().alpha(0f).setDuration(400)
            .withEndAction { binding.topBar.visibility = View.GONE }.start()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
        cancelBufferingTimeout()
        hideUiHandler.removeCallbacks(hideUiRunnable)
        hidePositionHandler.removeCallbacksAndMessages(null)
        preBufferHandler.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacksAndMessages(null)
        gestureOverlayHandler.removeCallbacksAndMessages(null)
        releasePreBuffer(); sleepTimer?.cancel(); player?.release(); player = null
    }

    override fun onConfigurationChanged(cfg: Configuration) { super.onConfigurationChanged(cfg); enterFullscreen() }

    // ════════════════════════════════════════════════════════════════════════
    // GESTURE
    // ════════════════════════════════════════════════════════════════════════

    private fun setupGestureDetector() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() { override fun onDown(e: MotionEvent) = true })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                showTopBarTemporarily()
                gestureStartX = event.x; gestureStartY = event.y
                isVolumeGesture = event.x > resources.displayMetrics.widthPixels / 2f
                audioManager?.let { initialVolume = it.getStreamVolume(AudioManager.STREAM_MUSIC) }
                initialBrightness = window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
            }
            MotionEvent.ACTION_MOVE -> handleGestureMove(event)
        }
        return super.onTouchEvent(event)
    }

    private fun handleGestureMove(event: MotionEvent) {
        val ratio = (gestureStartY - event.y) / resources.displayMetrics.heightPixels
        if (isVolumeGesture) {
            val v = (initialVolume + ratio * maxVolume).toInt().coerceIn(0, maxVolume)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            showGestureOverlay("🔊 ${(v * 100f / maxVolume).toInt()}%")
        } else {
            val b = (initialBrightness + ratio).coerceIn(0.01f, 1f)
            window.attributes = window.attributes.also { it.screenBrightness = b }
            showGestureOverlay("☀ ${(b * 100).toInt()}%")
        }
    }

    private fun showGestureOverlay(text: String) {
        binding.tvGestureOverlay?.let { tv ->
            tv.text = text; tv.visibility = View.VISIBLE; tv.animate().cancel(); tv.alpha = 1f
            gestureOverlayHandler.removeCallbacksAndMessages(null)
            gestureOverlayHandler.postDelayed({
                tv.animate().alpha(0f).setDuration(300).withEndAction { tv.visibility = View.GONE }.start()
            }, 800)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LOCK ORIENTASI
    // ════════════════════════════════════════════════════════════════════════

    private fun toggleOrientationLock() {
        isOrientationLocked = !isOrientationLocked
        requestedOrientation = if (isOrientationLocked)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        binding.btnLockOrientation?.setImageResource(
            if (isOrientationLocked) R.drawable.ic_screen_lock_landscape else R.drawable.ic_fullscreen
        )
        showToast(if (isOrientationLocked) "🔒 Landscape terkunci" else "🔓 Rotasi otomatis")
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
