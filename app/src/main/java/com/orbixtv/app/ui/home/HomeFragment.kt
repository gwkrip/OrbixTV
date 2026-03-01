package com.orbixtv.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbixtv.app.R
import com.orbixtv.app.data.Channel
import com.orbixtv.app.data.ChannelGroup
import com.orbixtv.app.databinding.FragmentHomeBinding
import com.orbixtv.app.ui.MainViewModel
import com.orbixtv.app.ui.player.PlayerActivity
import com.orbixtv.app.ui.settings.PlaylistSettingsActivity
import com.orbixtv.app.ui.tv.TvGroupAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var searchAdapter: ChannelAdapter
    private var tvGroupAdapter: TvGroupAdapter? = null
    private var tvChannelAdapter: ChannelAdapter? = null

    private var isSearchActive = false
    private var isTvLayout = false
    // #4: Job untuk debounce — dibatalkan jika user masih mengetik
    private var searchDebounceJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Deteksi TV/tablet layout berdasarkan keberadaan panel kiri (empty_panel hanya ada di sw720dp)
        isTvLayout = binding.root.findViewById<View?>(R.id.empty_panel) != null

        if (isTvLayout) {
            setupTvAdapters()
        } else {
            setupPhoneAdapters()
        }

        setupSearch()
        setupSettingsButton()
        observeData()
    }

    // ========================
    // Phone layout (expandable group list)
    // ========================

    private fun setupPhoneAdapters() {
        groupAdapter = GroupAdapter { channel -> openPlayer(channel) }
        binding.rvGroups.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = groupAdapter
        }

        searchAdapter = ChannelAdapter { channel -> openPlayer(channel) }
        binding.rvSearch.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
        }
    }

    // ========================
    // TV layout (2-panel: grup kiri, channel kanan)
    // ========================

    private fun setupTvAdapters() {
        // Panel kiri: grup list (klik → tampilkan channel di panel kanan)
        tvGroupAdapter = TvGroupAdapter { group -> showChannelsForGroup(group) }
        binding.rvGroups.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tvGroupAdapter
        }

        // Panel kanan: channel list (grid 2 kolom untuk memanfaatkan lebar layar)
        tvChannelAdapter = ChannelAdapter { channel -> openPlayer(channel) }
        binding.rvSearch.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = tvChannelAdapter
            visibility = View.VISIBLE
        }
    }

    private fun showChannelsForGroup(group: ChannelGroup) {
        binding.root.findViewById<View?>(R.id.empty_panel)?.visibility = View.GONE
        binding.rvSearch.visibility = View.VISIBLE
        tvChannelAdapter?.submitList(group.channels)
        binding.tvSearchCount?.text = "${group.flagEmoji} ${group.name}  —  ${group.channels.size} saluran"
        binding.tvSearchCount?.visibility = View.VISIBLE
    }

    // ========================
    // Shared setup
    // ========================

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText ?: ""
                isSearchActive = query.isNotBlank()

                // Update visibilitas panel langsung (tidak perlu debounce)
                if (isTvLayout) {
                    if (isSearchActive) {
                        binding.rvSearch.visibility = View.VISIBLE
                        binding.root.findViewById<View?>(R.id.empty_panel)?.visibility = View.GONE
                    }
                } else {
                    if (isSearchActive) {
                        binding.rvSearch.visibility = View.VISIBLE
                        binding.rvGroups.visibility = View.GONE
                    } else {
                        binding.rvSearch.visibility = View.GONE
                        binding.rvGroups.visibility = View.VISIBLE
                        binding.tvSearchCount?.visibility = View.GONE
                    }
                }

                // #4: Debounce 300ms — batalkan job sebelumnya, tunggu user berhenti mengetik
                searchDebounceJob?.cancel()
                searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    viewModel.search(query)
                }

                return true
            }
        })
    }

    private fun setupSettingsButton() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(requireContext(), PlaylistSettingsActivity::class.java))
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                if (!isTvLayout) {
                    binding.rvGroups.visibility = if (loading) View.GONE else View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.groups.collectLatest { groups ->
                val totalChannels = groups.sumOf { it.channels.size }
                binding.tvChannelCount.text = "$totalChannels saluran"

                if (isTvLayout) {
                    tvGroupAdapter?.submitList(groups)
                    // Auto-select grup pertama saat data pertama kali loaded
                    if (groups.isNotEmpty() && tvChannelAdapter?.currentList.isNullOrEmpty()) {
                        showChannelsForGroup(groups.first())
                        tvGroupAdapter?.let { it.notifyItemChanged(0) }
                    }
                } else {
                    groupAdapter.submitList(groups)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.searchResults.collectLatest { results ->
                if (isSearchActive) {
                    if (isTvLayout) {
                        tvChannelAdapter?.submitList(results)
                    } else {
                        searchAdapter.submitList(results)
                    }
                    binding.tvSearchCount?.text = if (results.isEmpty()) "Tidak ditemukan"
                    else "${results.size} hasil pencarian"
                    binding.tvSearchCount?.visibility = View.VISIBLE
                } else if (!isTvLayout) {
                    searchAdapter.submitList(emptyList())
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadError.collectLatest { error ->
                binding.tvPlaylistWarning?.visibility = if (error != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openPlayer(channel: Channel) {
        viewModel.onChannelWatched(channel.id)
        val allChannels = viewModel.getAllChannels()
        val channelIndex = allChannels.indexOfFirst { it.id == channel.id }
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
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
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
