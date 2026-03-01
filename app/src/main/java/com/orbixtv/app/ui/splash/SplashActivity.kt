package com.orbixtv.app.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.orbixtv.app.MainActivity
import com.orbixtv.app.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    private val handler = Handler(Looper.getMainLooper())
    private val launchRunnable = Runnable {
        if (!isFinishing) {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ivLogo.alpha    = 0f
        binding.tvAppName.alpha = 0f
        binding.tvTagline.alpha = 0f

        binding.ivLogo.animate().alpha(1f).setDuration(800).start()
        binding.tvAppName.animate().alpha(1f).setDuration(800).setStartDelay(300).start()
        binding.tvTagline.animate().alpha(1f).setDuration(800).setStartDelay(600).start()

        handler.postDelayed(launchRunnable, 2500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(launchRunnable)
    }
}
