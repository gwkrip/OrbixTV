package com.orbixtv.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbixtv.app.data.Channel
import com.orbixtv.app.databinding.FragmentFavoritesBinding
import com.orbixtv.app.ui.MainViewModel
import com.orbixtv.app.ui.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: ChannelAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ChannelAdapter { channel -> openPlayer(channel) }

        // Deteksi layar lebar (sw720dp): grid 3 kolom, phone: linear
        val isLargeScreen = resources.displayMetrics.widthPixels /
                resources.displayMetrics.density >= 720

        binding.rvFavorites.apply {
            layoutManager = if (isLargeScreen)
                GridLayoutManager(requireContext(), 3)
            else
                LinearLayoutManager(requireContext())
            adapter = this@FavoritesFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.favorites.collectLatest { favs ->
                adapter.submitList(favs)
                // #12: Update jumlah
                binding.tvFavoritesCount.text = if (favs.isEmpty()) "" else "${favs.size} favorit"
                // #13: Animasi fade transisi empty state ↔ list
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
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, channelIndex)
            putExtra(PlayerActivity.EXTRA_CHANNEL_COUNT, allChannels.size)
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
