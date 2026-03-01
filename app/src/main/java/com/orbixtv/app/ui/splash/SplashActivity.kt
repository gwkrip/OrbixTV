package com.orbixtv.app.ui.splash

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.orbixtv.app.BuildConfig
import com.orbixtv.app.MainActivity
import com.orbixtv.app.databinding.ActivitySplashBinding
import com.orbixtv.app.ui.MainViewModel
import com.orbixtv.app.worker.PlaylistCheckWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // #15: Tampilkan versi app
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        // ⑫ Jadwalkan cek playlist periodik (setiap 6 jam)
        schedulePlaylistCheck()

        binding.ivLogo.alpha    = 0f
        binding.tvAppName.alpha = 0f
        binding.tvTagline.alpha = 0f

        binding.ivLogo.animate().alpha(1f).setDuration(800).start()
        binding.tvAppName.animate().alpha(1f).setDuration(800).setStartDelay(300).start()
        binding.tvTagline.animate().alpha(1f).setDuration(800).setStartDelay(600).start()

        val viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        lifecycleScope.launch {
            val minSplashJob = launch { delay(1_500) }

            // #2: Tampilkan ProgressBar setelah 2 detik jika masih loading
            launch {
                delay(2_000)
                if (!isFinishing && viewModel.isLoading.value) {
                    binding.progressLoading.visibility = View.VISIBLE
                }
            }

            var waited = false
            viewModel.isLoading.collect { loading ->
                if (!loading && !waited) {
                    waited = true
                    minSplashJob.join()
                    if (!isFinishing) {
                        binding.progressLoading.visibility = View.GONE
                        startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            overrideActivityTransition(
                                OVERRIDE_TRANSITION_OPEN,
                                android.R.anim.fade_in,
                                android.R.anim.fade_out
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                        finish()
                    }
                }
            }
        }
    }

    private fun schedulePlaylistCheck() {
        val request = PeriodicWorkRequestBuilder<PlaylistCheckWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PlaylistCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
