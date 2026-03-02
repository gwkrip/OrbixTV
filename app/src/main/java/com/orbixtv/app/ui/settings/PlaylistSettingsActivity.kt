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

        refreshSourceLabel()

        // Tampilkan URL yang sedang aktif di field input
        binding.etPlaylistUrl.setText(viewModel.getPlaylistUrl())

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
            refreshSourceLabel()
            Toast.makeText(this, "Memuat playlist dari URL...", Toast.LENGTH_SHORT).show()
        }

        binding.btnReset.setOnClickListener {
            val defaultUrl = viewModel.getDefaultPlaylistUrl()
            AlertDialog.Builder(this)
                .setTitle("Reset ke URL Default")
                .setMessage("Playlist kustom akan dihapus dan aplikasi akan menggunakan URL default:\n\n$defaultUrl")
                .setPositiveButton("Reset") { _, _ ->
                    viewModel.resetToDefaultPlaylist()
                    binding.etPlaylistUrl.setText(defaultUrl)
                    refreshSourceLabel()
                    Toast.makeText(this, "Kembali ke URL default", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun refreshSourceLabel() {
        binding.tvCurrentSource.text = if (viewModel.isUsingDefaultUrl()) {
            "Sumber aktif: URL Default"
        } else {
            "Sumber aktif: URL Kustom"
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
