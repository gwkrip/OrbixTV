package com.orbixtv.app.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbixtv.app.R
import com.orbixtv.app.data.Channel
import com.orbixtv.app.databinding.FragmentFavoritesBinding
import com.orbixtv.app.ui.MainViewModel
import com.orbixtv.app.ui.player.PlayerActivity
import com.facebook.shimmer.ShimmerFrameLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: ChannelAdapter

    private var allFavorites: List<Channel> = emptyList()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        handleImport(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ChannelAdapter(viewLifecycleOwner) { channel -> openPlayer(channel) }

        val isLargeScreen = resources.displayMetrics.widthPixels /
                resources.displayMetrics.density >= 720

        binding.rvFavorites.apply {
            layoutManager = if (isLargeScreen)
                GridLayoutManager(requireContext(), 3)
            else
                LinearLayoutManager(requireContext())
            adapter = this@FavoritesFragment.adapter
        }

        setupSearch()
        setupExportImport()
        observeLoadingState()
        observeFavorites()
    }

    private fun setupSearch() {
        binding.searchViewFavorites?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText?.trim() ?: ""
                val filtered = if (query.isEmpty()) allFavorites
                else allFavorites.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.group.contains(query, ignoreCase = true)
                }
                adapter.submitList(filtered)
                binding.tvFavoritesCount.text = if (filtered.isEmpty()) ""
                    else "${filtered.size} dari ${allFavorites.size} favorit"
                return true
            }
        })
    }

    private fun setupExportImport() {
        binding.btnExport?.setOnClickListener {
            viewModel.exportFavorites { file ->
                if (file != null) showExportSuccess(file)
                else showSimpleMessage(getString(R.string.export_failed))
            }
        }
        binding.btnImport?.setOnClickListener {
            importLauncher.launch("application/json")
        }
    }

    private fun handleImport(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return
            val tempFile = File(requireContext().cacheDir, "import_favorites.json")
            tempFile.outputStream().use { out -> inputStream.copyTo(out) }

            viewModel.importFavorites(tempFile) { count ->
                tempFile.delete()
                val msg = when {
                    count > 0  -> getString(R.string.import_success, count)
                    count == 0 -> getString(R.string.import_no_new)
                    else       -> getString(R.string.import_failed)
                }
                showSimpleMessage(msg)
            }
        } catch (e: Exception) {
            showSimpleMessage(getString(R.string.import_failed))
        }
    }

    private fun showExportSuccess(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.export_success_title))
            .setMessage(getString(R.string.export_success_message, file.absolutePath))
            .setPositiveButton(getString(R.string.share)) { _, _ ->
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
                startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
            .setNegativeButton(getString(R.string.ok), null)
            .show()
    }

    private fun showSimpleMessage(message: String) {
        AlertDialog.Builder(requireContext())
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun observeLoadingState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                if (loading) {
                    binding.shimmerFavorites.visibility = View.VISIBLE
                    binding.shimmerFavorites.startShimmer()
                } else {
                    binding.shimmerFavorites.stopShimmer()
                    binding.shimmerFavorites.animate()
                        .alpha(0f)
                        .setDuration(250)
                        .withEndAction {
                            binding.shimmerFavorites.visibility = View.GONE
                            binding.shimmerFavorites.alpha = 1f
                        }.start()
                }
            }
        }
    }

    private fun observeFavorites() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.favorites.collectLatest { favs ->
                allFavorites = favs
                adapter.submitList(favs)
                binding.tvFavoritesCount.text = if (favs.isEmpty()) "" else "${favs.size} favorit"

                binding.searchViewFavorites?.visibility =
                    if (favs.size > 5) View.VISIBLE else View.GONE

                val isEmpty = favs.isEmpty()
                if (isEmpty && binding.rvFavorites.visibility == View.VISIBLE) {
                    binding.rvFavorites.animate().alpha(0f).setDuration(200)
                        .withEndAction {
                            binding.rvFavorites.visibility = View.GONE
                            binding.emptyState.alpha = 0f
                            binding.emptyState.visibility = View.VISIBLE
                            binding.emptyState.animate().alpha(1f).setDuration(200).start()
                        }.start()
                } else if (!isEmpty && binding.emptyState.visibility == View.VISIBLE) {
                    binding.emptyState.animate().alpha(0f).setDuration(200)
                        .withEndAction {
                            binding.emptyState.visibility = View.GONE
                            binding.rvFavorites.alpha = 0f
                            binding.rvFavorites.visibility = View.VISIBLE
                            binding.rvFavorites.animate().alpha(1f).setDuration(200).start()
                        }.start()
                } else {
                    binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    binding.rvFavorites.visibility = if (isEmpty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun openPlayer(channel: Channel) {
        viewModel.onChannelWatched(channel.id)
        val allChannels = viewModel.getAllChannels()
        val channelIndex = allChannels.indexOfFirst { it.id == channel.id }
        startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
            putExtra(PlayerActivity.EXTRA_USER_AGENT, channel.userAgent)
            putExtra(PlayerActivity.EXTRA_LICENSE_TYPE, channel.licenseType)
            putExtra(PlayerActivity.EXTRA_LICENSE_KEY, channel.licenseKey)
            putExtra(PlayerActivity.EXTRA_REFERER, channel.referer)
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            putExtra(PlayerActivity.EXTRA_MIME_TYPE_HINT, channel.mimeTypeHint)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, channelIndex)
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
