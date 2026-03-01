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
import com.orbixtv.app.databinding.FragmentRecentBinding
import com.orbixtv.app.ui.MainViewModel
import com.orbixtv.app.ui.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RecentFragment : Fragment() {

    private var _binding: FragmentRecentBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: ChannelAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ChannelAdapter { channel -> openPlayer(channel) }

        val isLargeScreen = resources.displayMetrics.widthPixels /
                resources.displayMetrics.density >= 720

        binding.rvRecent.apply {
            layoutManager = if (isLargeScreen)
                GridLayoutManager(requireContext(), 3)
            else
                LinearLayoutManager(requireContext())
            adapter = this@RecentFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                if (!loading) refreshRecent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    private fun refreshRecent() {
        val recent = viewModel.getRecentChannels()
        adapter.submitList(recent)
        binding.emptyState.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        binding.rvRecent.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
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
