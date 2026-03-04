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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var tvGroupAdapter: TvGroupAdapter
    private lateinit var tvChannelAdapter: ChannelAdapter

    private var isSearchActive = false
    private var searchDebounceJob: Job? = null

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
        setupAdapters()
        setupSearch()
        setupSettingsButton()
        setupSortFilter()
        observeData()
    }

    private fun setupAdapters() {
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
        tvChannelAdapter.submitList(sorted)
        binding.tvSearchCount.text = "${group.flagEmoji} ${group.name}  —  ${sorted.size} saluran"
        binding.tvSearchCount.visibility = View.VISIBLE
    }

    private fun setupSortFilter() {
        binding.btnSortFilter?.setOnClickListener { showSortFilterDialog() }
    }

    private fun showSortFilterDialog() {
        val sortLabels   = arrayOf("Default", "Nama A-Z", "Nama Z-A", "Tipe Stream")
        val sortValues   = SortOrder.values()
        val filterLabels = arrayOf("Semua", "HLS", "DASH", "RTMP")
        val filterValues = StreamFilter.values()

        var selectedSort   = sortValues.indexOf(viewModel.sortOrder.value)
        var selectedFilter = filterValues.indexOf(viewModel.streamFilter.value)

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        container.addView(android.widget.TextView(requireContext()).apply {
            text = getString(R.string.sort_filter_title)
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 13f; setPadding(0, 0, 0, 8)
        })
        val sortGroup = android.widget.RadioGroup(requireContext())
        sortLabels.forEachIndexed { i, label ->
            sortGroup.addView(android.widget.RadioButton(requireContext()).apply {
                text = label; id = View.generateViewId(); isChecked = i == selectedSort
                setOnCheckedChangeListener { _, checked -> if (checked) selectedSort = i }
            })
        }
        container.addView(sortGroup)
        container.addView(View(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(0, 20, 0, 16) }
            setBackgroundColor(0x1AFFFFFF)
        })
        container.addView(android.widget.TextView(requireContext()).apply {
            text = getString(R.string.filter_stream_type)
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 13f; setPadding(0, 0, 0, 8)
        })
        val filterGroup = android.widget.RadioGroup(requireContext())
        filterLabels.forEachIndexed { i, label ->
            filterGroup.addView(android.widget.RadioButton(requireContext()).apply {
                text = label; id = View.generateViewId(); isChecked = i == selectedFilter
                setOnCheckedChangeListener { _, checked -> if (checked) selectedFilter = i }
            })
        }
        container.addView(filterGroup)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.sort_filter_title))
            .setView(android.widget.ScrollView(requireContext()).apply { addView(container) })
            .setPositiveButton(getString(R.string.apply_filter)) { _, _ ->
                viewModel.setSortOrder(sortValues[selectedSort])
                viewModel.setStreamFilter(filterValues[selectedFilter])
                applyCurrentSortFilter()
            }
            .setNegativeButton(getString(R.string.dialog_action_cancel), null)
            .show()
    }

    private fun applyCurrentSortFilter() {
        val sorted = viewModel.groups.value.map { g ->
            g.copy(channels = viewModel.getFilteredSortedChannels(g.channels))
        }
        tvGroupAdapter.submitList(sorted)
        val activeChannelId = tvChannelAdapter.currentList.firstOrNull()?.id
        if (activeChannelId != null) {
            val activeGroup = viewModel.groups.value.firstOrNull { g ->
                g.channels.any { ch -> ch.id == activeChannelId }
            }
            activeGroup?.let { showChannelsForGroup(it) }
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchView.clearFocus()
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(binding.searchView.windowToken, 0)
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText ?: ""
                isSearchActive = query.isNotBlank()
                if (isSearchActive) {
                    binding.rvSearch.visibility = View.GONE
                    binding.shimmerSearch?.visibility = View.VISIBLE
                    binding.shimmerSearch?.startShimmer()
                    binding.root.findViewById<View?>(R.id.empty_panel)?.visibility = View.GONE
                } else {
                    binding.shimmerSearch?.stopShimmer()
                    binding.shimmerSearch?.visibility = View.GONE
                }
                searchDebounceJob?.cancel()
                searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    binding.shimmerSearch?.stopShimmer()
                    binding.shimmerSearch?.visibility = View.GONE
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
            duration = 600; repeatCount = android.view.animation.Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        binding.btnRefresh.startAnimation(spin)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                if (loading) {
                    binding.shimmerHome.visibility = View.VISIBLE
                    binding.shimmerHome.startShimmer()
                    binding.rvGroups.visibility = View.GONE
                } else {
                    binding.shimmerHome.stopShimmer()
                    binding.shimmerHome.animate().alpha(0f).setDuration(250).withEndAction {
                        binding.shimmerHome.visibility = View.GONE
                        binding.shimmerHome.alpha = 1f
                    }.start()
                    binding.btnRefresh.clearAnimation()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.groups.collectLatest { groups ->
                val totalChannels = groups.sumOf { it.channels.size }
                binding.tvChannelCount.text = "$totalChannels saluran"
                if (binding.rvGroups.visibility == View.GONE && groups.isNotEmpty()) {
                    binding.rvGroups.alpha = 0f
                    binding.rvGroups.visibility = View.VISIBLE
                    binding.rvGroups.animate().alpha(1f).setDuration(250).start()
                }
                tvGroupAdapter.submitList(groups)
                if (groups.isNotEmpty() && tvChannelAdapter.currentList.isNullOrEmpty()) {
                    showChannelsForGroup(groups.first())
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.searchResults.collectLatest { results ->
                if (isSearchActive) {
                    val sorted = viewModel.getFilteredSortedChannels(results)
                    tvChannelAdapter.submitList(sorted)
                    binding.tvSearchCount.text = if (sorted.isEmpty()) "Tidak ditemukan"
                        else "${sorted.size} hasil pencarian"
                    binding.tvSearchCount.visibility = View.VISIBLE
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
            putExtra(PlayerActivity.EXTRA_STREAM_TYPE, channel.streamType)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, channelIndex)
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
