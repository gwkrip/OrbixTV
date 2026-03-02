package com.orbixtv.app.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.orbixtv.app.databinding.ActivityPlaylistSettingsBinding
import com.orbixtv.app.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaylistSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistSettingsBinding
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupUI()
        observeState()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        val savedUrl = viewModel.getPlaylistUrl()
        if (savedUrl.isNotEmpty()) {
            binding.etPlaylistUrl.setText(savedUrl)
            binding.tvCurrentSource.text = "Sumber aktif: URL Eksternal"
        } else {
            binding.tvCurrentSource.text = "Sumber aktif: Playlist bawaan (assets)"
        }

        binding.btnApply.setOnClickListener {
            val url = binding.etPlaylistUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "URL tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, "URL harus dimulai dengan http:// atau https://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.setPlaylistUrl(url)
            binding.tvCurrentSource.text = "Sumber aktif: URL Eksternal"
            Toast.makeText(this, "Memuat playlist dari URL...", Toast.LENGTH_SHORT).show()
        }

        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset ke Playlist Bawaan")
                .setMessage("Playlist kustom akan dihapus dan aplikasi akan kembali menggunakan playlist bawaan.")
                .setPositiveButton("Reset") { _, _ ->
                    viewModel.resetToDefaultPlaylist()
                    binding.etPlaylistUrl.setText("")
                    binding.tvCurrentSource.text = "Sumber aktif: Playlist bawaan (assets)"
                    Toast.makeText(this, "Kembali ke playlist bawaan", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                binding.btnApply.isEnabled = !loading
                binding.btnReset.isEnabled = !loading
            }
        }

        lifecycleScope.launch {
            viewModel.loadError.collectLatest { error ->
                if (error != null) {
                    binding.tvError.visibility = View.VISIBLE
                    binding.tvError.text = "⚠️ $error\nMenggunakan playlist bawaan sebagai fallback."
                } else {
                    binding.tvError.visibility = View.GONE
                }
            }
        }
    }
}
