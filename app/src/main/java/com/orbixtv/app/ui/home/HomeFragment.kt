package com.orbixtv.app.ui.home

import android.app.Activity
import android.view.animation.LinearInterpolator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
import com.facebook.shimmer.ShimmerFrameLayout
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
    private var shimmerSearch: com.facebook.shimmer.ShimmerFrameLayout? = null

    // Launcher untuk PlaylistSettingsActivity — reload playlist jika ada perubahan
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.loadPlaylist()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isTvLayout = binding.root.findViewById<View?>(R.id.empty_panel) != null
        shimmerSearch = binding.root.findViewById(R.id.shimmer_search)

        if (isTvLayout) setupTvAdapters() else setupPhoneAdapters()

        setupSearch()
        setupSettingsButton()
        setupSortFilter()
        observeData()
    }

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
        binding.tvSearchCount.text = "${group.flagEmoji} ${group.name}  —  ${sorted.size} saluran"
        binding.tvSearchCount.visibility = View.VISIBLE
    }

    private fun setupSortFilter() {
        binding.btnSortFilter?.setOnClickListener { showSortFilterDialog() }
    }

    private fun showSortFilterDialog() {
        val sortLabels   = arrayOf("Default", "Nama A–Z", "Nama Z–A", "Tipe Stream")
        val sortValues   = SortOrder.values()
        val filterLabels = arrayOf("Semua", "HLS", "DASH", "RTMP")
        val filterValues = StreamFilter.values()

        var selectedSort   = sortValues.indexOf(viewModel.sortOrder.value)
        var selectedFilter = filterValues.indexOf(viewModel.streamFilter.value)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.sort_filter_title))
            .setSingleChoiceItems(sortLabels, selectedSort) { _, which -> selectedSort = which }
            .setPositiveButton(getString(R.string.next)) { _, _ ->
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

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText ?: ""
                isSearchActive = query.isNotBlank()

                if (isTvLayout) {
                    if (isSearchActive) {
                        binding.rvSearch.visibility = View.GONE
                        shimmerSearch?.visibility = View.VISIBLE
                        shimmerSearch?.startShimmer()
                        binding.root.findViewById<View?>(R.id.empty_panel)?.visibility = View.GONE
                    } else {
                        shimmerSearch?.stopShimmer()
                        shimmerSearch?.visibility = View.GONE
                    }
                } else {
                    if (isSearchActive) {
                        binding.searchPanel?.visibility = View.VISIBLE
                        binding.rvGroups.visibility = View.GONE
                        // Tampilkan skeleton, sembunyikan hasil lama
                        binding.rvSearch.visibility = View.GONE
                        shimmerSearch?.visibility = View.VISIBLE
                        shimmerSearch?.startShimmer()
                        binding.tvSearchCount.visibility = View.GONE
                    } else {
                        shimmerSearch?.stopShimmer()
                        shimmerSearch?.visibility = View.GONE
                        binding.searchPanel?.visibility = View.GONE
                        binding.rvGroups.visibility = View.VISIBLE
                        binding.tvSearchCount.visibility = View.GONE
                    }
                }

                searchDebounceJob?.cancel()
                searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    // Stop skeleton dan tampilkan hasil
                    shimmerSearch?.stopShimmer()
                    shimmerSearch?.visibility = View.GONE
                    if (isSearchActive) binding.rvSearch.visibility = View.VISIBLE
                    viewModel.search(query)
                }
                return true
            }
        })
    }

    private fun setupSettingsButton() {
        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(requireContext(), PlaylistSettingsActivity::class.java))
        }

        binding.btnRefresh.setOnClickListener {
            if (viewModel.isLoading.value) return@setOnClickListener
            startRefreshAnimation()
            viewModel.loadPlaylist()
            android.widget.Toast.makeText(requireContext(), "Memuat ulang playlist...", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRefreshAnimation() {
        val spin = android.view.animation.RotateAnimation(
            0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 600
            repeatCount = android.view.animation.Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        binding.btnRefresh.startAnimation(spin)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                if (loading) {
                    // Tampilkan skeleton, sembunyikan konten
                    binding.shimmerHome.visibility = View.VISIBLE
                    binding.shimmerHome.startShimmer()
                    if (!isTvLayout) {
                        binding.rvGroups.visibility = View.GONE
                    }
                } else {
                    // Stop shimmer + fade out skeleton, fade in konten
                    binding.shimmerHome.stopShimmer()
                    binding.shimmerHome.animate()
                        .alpha(0f)
                        .setDuration(250)
                        .withEndAction {
                            binding.shimmerHome.visibility = View.GONE
                            binding.shimmerHome.alpha = 1f
                        }.start()

                    binding.btnRefresh.clearAnimation()

                    if (!isTvLayout) {
                        binding.rvGroups.alpha = 0f
                        binding.rvGroups.visibility = View.VISIBLE
                        binding.rvGroups.animate().alpha(1f).setDuration(250).start()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.groups.collectLatest { groups ->
                val totalChannels = groups.sumOf { it.channels.size }
                binding.tvChannelCount.text = "$totalChannels saluran"

                if (isTvLayout) {
                    // Tampilkan rv_groups TV saat data pertama kali tiba
                    if (binding.rvGroups.visibility == View.GONE && groups.isNotEmpty()) {
                        binding.rvGroups.alpha = 0f
                        binding.rvGroups.visibility = View.VISIBLE
                        binding.rvGroups.animate().alpha(1f).setDuration(250).start()
                    }
                    tvGroupAdapter?.submitList(groups)
                    if (groups.isNotEmpty() && tvChannelAdapter?.currentList.isNullOrEmpty()) {
                        showChannelsForGroup(groups.first())
                    }
                } else {
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
                    binding.tvSearchCount.text = if (sorted.isEmpty()) "Tidak ditemukan"
                        else "${sorted.size} hasil pencarian"
                    binding.tvSearchCount.visibility = View.VISIBLE
                } else if (!isTvLayout) {
                    searchAdapter.submitList(emptyList())
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadError.collectLatest { error ->
                binding.tvPlaylistWarning.visibility =
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
            putExtra(PlayerActivity.EXTRA_MIME_TYPE_HINT, channel.mimeTypeHint)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, channelIndex)
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
