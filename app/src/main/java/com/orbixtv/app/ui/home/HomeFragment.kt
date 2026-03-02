package com.orbixtv.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbixtv.app.R
import com.orbixtv.app.data.Channel
import com.orbixtv.app.data.ChannelGroup
import com.orbixtv.app.data.SortOrder
import com.orbixtv.app.data.StreamFilter
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
    private var searchDebounceJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isTvLayout = binding.root.findViewById<View?>(R.id.empty_panel) != null

        if (isTvLayout) setupTvAdapters() else setupPhoneAdapters()

        setupSearch()
        setupSettingsButton()
        setupSortFilter()   // ⑦
        observeData()
    }

    // ========================
    // Phone
    // ========================

    private fun setupPhoneAdapters() {
        groupAdapter = GroupAdapter(viewLifecycleOwner) { channel -> openPlayer(channel) }
        binding.rvGroups.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = groupAdapter
        }
        searchAdapter = ChannelAdapter(viewLifecycleOwner) { channel -> openPlayer(channel) }
        binding.rvSearch.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
        }
    }

    // ========================
    // TV / Tablet
    // ========================

    private fun setupTvAdapters() {
        tvGroupAdapter = TvGroupAdapter { group -> showChannelsForGroup(group) }
        binding.rvGroups.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tvGroupAdapter
        }
        tvChannelAdapter = ChannelAdapter(viewLifecycleOwner) { channel -> openPlayer(channel) }
        binding.rvSearch.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = tvChannelAdapter
            visibility = View.VISIBLE
        }
    }

    private fun showChannelsForGroup(group: ChannelGroup) {
        binding.root.findViewById<View?>(R.id.empty_panel)?.visibility = View.GONE
        binding.rvSearch.visibility = View.VISIBLE
        val sorted = viewModel.getFilteredSortedChannels(group.channels)
        tvChannelAdapter?.submitList(sorted)
        binding.tvSearchCount?.text = "${group.flagEmoji} ${group.name}  —  ${sorted.size} saluran"
        binding.tvSearchCount?.visibility = View.VISIBLE
    }

    // ========================
    // ⑦ Sort & Filter
    // ========================

    private fun setupSortFilter() {
        binding.btnSortFilter?.setOnClickListener { showSortFilterDialog() }
    }

    private fun showSortFilterDialog() {
        val sortLabels = arrayOf("Default", "Nama A–Z", "Nama Z–A", "Tipe Stream")
        val sortValues = SortOrder.values()
        val filterLabels = arrayOf("Semua", "HLS", "DASH", "RTMP")
        val filterValues = StreamFilter.values()

        var selectedSort = sortValues.indexOf(viewModel.sortOrder.value)
        var selectedFilter = filterValues.indexOf(viewModel.streamFilter.value)

        // Dialog sort
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.sort_filter_title))
            .setSingleChoiceItems(sortLabels, selectedSort) { _, which -> selectedSort = which }
            .setPositiveButton(getString(R.string.next)) { _, _ ->
                // Dialog filter
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.filter_stream_type))
                    .setSingleChoiceItems(filterLabels, selectedFilter) { _, which ->
                        selectedFilter = which
                    }
                    .setPositiveButton(getString(R.string.apply_filter)) { _, _ ->
                        viewModel.setSortOrder(sortValues[selectedSort])
                        viewModel.setStreamFilter(filterValues[selectedFilter])
                        applyCurrentSortFilter()
                    }
                    .setNegativeButton(getString(R.string.dialog_action_cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.dialog_action_cancel), null)
            .show()
    }

    private fun applyCurrentSortFilter() {
        if (isTvLayout) {
            // TV: refresh daftar grup dan panel kanan jika ada grup aktif
            val sorted = viewModel.groups.value.map { g ->
                g.copy(channels = viewModel.getFilteredSortedChannels(g.channels))
            }
            tvGroupAdapter?.submitList(sorted)
            val activeChannelId = tvChannelAdapter?.currentList?.firstOrNull()?.id
            if (activeChannelId != null) {
                val activeGroup = viewModel.groups.value.firstOrNull { g ->
                    g.channels.any { ch -> ch.id == activeChannelId }
                }
                activeGroup?.let { showChannelsForGroup(it) }
            }
        } else if (isSearchActive) {
            val filtered = viewModel.getFilteredSortedChannels(viewModel.searchResults.value)
            searchAdapter.submitList(filtered)
        } else {
            val newGroups = viewModel.groups.value.map { group ->
                group.copy(channels = viewModel.getFilteredSortedChannels(group.channels))
            }
            groupAdapter.submitList(newGroups)
        }
    }

    // ========================
    // Search
    // ========================

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText ?: ""
                isSearchActive = query.isNotBlank()

                if (isTvLayout) {
                    if (isSearchActive) {
                        binding.rvSearch.visibility = View.VISIBLE
                        binding.root.findViewById<View?>(R.id.empty_panel)?.visibility = View.GONE
                    }
                } else {
                    if (isSearchActive) {
                        binding.searchPanel?.visibility = View.VISIBLE
                        binding.rvGroups.visibility = View.GONE
                    } else {
                        binding.searchPanel?.visibility = View.GONE
                        binding.rvGroups.visibility = View.VISIBLE
                        binding.tvSearchCount?.visibility = View.GONE
                    }
                }

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
                    if (groups.isNotEmpty() && tvChannelAdapter?.currentList.isNullOrEmpty()) {
                        showChannelsForGroup(groups.first())
                    }
                } else {
                    // Apply current sort/filter saat data pertama load
                    val sorted = groups.map { g ->
                        g.copy(channels = viewModel.getFilteredSortedChannels(g.channels))
                    }
                    groupAdapter.submitList(sorted)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.searchResults.collectLatest { results ->
                if (isSearchActive) {
                    val sorted = viewModel.getFilteredSortedChannels(results)
                    if (isTvLayout) tvChannelAdapter?.submitList(sorted)
                    else searchAdapter.submitList(sorted)
                    binding.tvSearchCount?.text = if (sorted.isEmpty()) "Tidak ditemukan"
                        else "${sorted.size} hasil pencarian"
                    binding.tvSearchCount?.visibility = View.VISIBLE
                } else if (!isTvLayout) {
                    searchAdapter.submitList(emptyList())
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadError.collectLatest { error ->
                binding.tvPlaylistWarning?.visibility =
                    if (error != null) View.VISIBLE else View.GONE
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
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
