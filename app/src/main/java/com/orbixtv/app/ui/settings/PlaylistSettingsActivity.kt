package com.orbixtv.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.orbixtv.app.databinding.ActivityPlaylistSettingsBinding
import com.orbixtv.app.ui.MainViewModel

class PlaylistSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistSettingsBinding
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        setupUI()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSupport.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/vryptdev")))
        }

        refreshSourceLabel()
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
            // Simpan URL saja — reload dilakukan HomeFragment via RESULT_OK
            viewModel.saveUrl(url)
            refreshSourceLabel()
            setResult(RESULT_OK)
            Toast.makeText(this, "URL disimpan. Memuat ulang playlist...", Toast.LENGTH_SHORT).show()
        }

        binding.btnReset.setOnClickListener {
            val defaultUrl = viewModel.getDefaultPlaylistUrl()
            AlertDialog.Builder(this)
                .setTitle("Reset ke URL Default")
                .setMessage("Playlist kustom akan dihapus dan aplikasi akan menggunakan URL default:\n\n$defaultUrl")
                .setPositiveButton("Reset") { _, _ ->
                    viewModel.resetUrl()
                    binding.etPlaylistUrl.setText(defaultUrl)
                    refreshSourceLabel()
                    setResult(RESULT_OK)
                    Toast.makeText(this, "Kembali ke URL default. Memuat ulang...", Toast.LENGTH_SHORT).show()
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
}
