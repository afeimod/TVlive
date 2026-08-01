package com.tvlive.app.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.tvlive.app.R
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.PlayHistory
import com.tvlive.app.databinding.ActivityMainBinding
import com.tvlive.app.ui.RefreshState
import com.tvlive.app.ui.MainViewModel
import com.tvlive.app.ui.player.PlayerActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val adapter: ChannelGridAdapter = ChannelGridAdapter(
        onClick = { channel, position, list ->
            launchPlayer(list, position)
            viewModel.addHistory(channel)
        },
        onLongClick = { channel, _ ->
            viewModel.toggleFavorite(channel)
            Toast.makeText(
                this,
                if (channel.favorite) R.string.action_unfavorite else R.string.action_favorite,
                Toast.LENGTH_SHORT
            ).show()
        }
    )

    /** 当前 Tab 过滤值: null=全部, 分组名=按组过滤, favorites/history=特殊 */
    private var currentFilter: String? = null
    private var searchKeyword: String? = null

    private var allChannelList: List<Channel> = emptyList()
    private var favList: List<Channel> = emptyList()
    private var historyList: List<PlayHistory> = emptyList()
    private var isLoading = false
    private var hasAutoPlayed = false  // 首次自动播放标记

    private val tabs = listOf(
        Tab(R.string.tab_all, null),
        Tab(R.string.tab_cctv, Channel.GROUP_CCTV),
        Tab(R.string.tab_satellite, Channel.GROUP_SATELLITE),
        Tab(R.string.tab_local, Channel.GROUP_LOCAL),
        Tab(R.string.tab_hk, Channel.GROUP_HK_MACAO_TW),
        Tab(R.string.tab_international, Channel.GROUP_INTERNATIONAL),
        Tab(R.string.tab_favorites, FILTER_FAVORITES),
        Tab(R.string.tab_history, FILTER_HISTORY)
    )
    private val tabViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupTabs()
        setupListeners()
        observeData()

        // 首次启动自动刷新源
        val prefs = getSharedPreferences("tvlive_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("sources_loaded", false)) {
            viewModel.refreshSources()
        }
    }

    private fun setupRecyclerView() {
        binding.rvChannels.layoutManager = GridLayoutManager(this, 6)
        binding.rvChannels.adapter = adapter
        binding.rvChannels.setHasFixedSize(false)
    }

    private fun setupTabs() {
        binding.tabContainer.removeAllViews()
        tabViews.clear()
        tabs.forEachIndexed { index, tab ->
            val tv = TextView(this)
            tv.applyTabStyle()
            tv.text = getString(tab.labelRes)
            tv.tag = tab.filterValue
            tv.isSelected = index == 0
            tv.setOnClickListener { selectTab(index) }
            tv.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) selectTab(index)
            }
            binding.tabContainer.addView(tv)
            tabViews.add(tv)
        }
    }

    private fun TextView.applyTabStyle() {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 6
            marginEnd = 6
        }
        layoutParams = lp
        setPadding(40, 16, 40, 16)
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setBackgroundResource(R.drawable.bg_tab_selector)
        isFocusable = true
        isClickable = true
        textSize = 14f
    }

    private fun selectTab(index: Int) {
        searchKeyword = null
        currentFilter = tabs[index].filterValue
        tabViews.forEachIndexed { i, v -> v.isSelected = i == index }
        applyFilter()
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener {
            viewModel.refreshSources()
        }
        binding.btnRefreshSources.setOnClickListener {
            viewModel.refreshSources()
        }
        binding.btnSearch.setOnClickListener {
            showSearchDialog()
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, com.tvlive.app.ui.settings.SettingsActivity::class.java))
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allChannels.collect { list ->
                        allChannelList = list
                        applyFilter()
                    }
                }
                launch {
                    viewModel.favorites.collect { list ->
                        favList = list
                        if (currentFilter == FILTER_FAVORITES) applyFilter()
                    }
                }
                launch {
                    viewModel.history.collect { list ->
                        historyList = list
                        if (currentFilter == FILTER_HISTORY) applyFilter()
                    }
                }
                launch {
                    viewModel.refreshState.collect { state -> handleRefreshState(state) }
                }
            }
        }
    }

    private fun handleRefreshState(state: RefreshState) {
        when (state) {
            is RefreshState.Idle -> {
                isLoading = false
                binding.progressBar.visibility = View.GONE
                updateStatus(adapter.itemCount)
            }
            is RefreshState.Loading -> {
                isLoading = true
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatus.text = getString(
                    R.string.status_loading, state.current, state.total, state.sourceName
                )
                binding.emptyView.visibility = View.GONE
            }
            is RefreshState.Done -> {
                isLoading = false
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = getString(
                    R.string.status_done, state.result.successCount, state.result.totalChannels
                )
                binding.emptyView.visibility =
                    if (adapter.itemCount == 0) View.VISIBLE else View.GONE

                // 标记已加载，首次刷新后自动进入 CCTV-1 播放
                getSharedPreferences("tvlive_prefs", MODE_PRIVATE)
                    .edit().putBoolean("sources_loaded", true).apply()
                if (!hasAutoPlayed && state.result.totalChannels > 0) {
                    hasAutoPlayed = true
                    autoPlayCctv1()
                }
            }
        }
    }

    /** 根据当前 Tab / 搜索关键词计算并刷新频道列表 */
    private fun applyFilter() {
        val base: List<Channel> = when {
            searchKeyword != null ->
                allChannelList.filter {
                    it.name.contains(searchKeyword!!, ignoreCase = true)
                }
            currentFilter == null -> allChannelList
            currentFilter == FILTER_FAVORITES -> favList
            currentFilter == FILTER_HISTORY ->
                historyList.mapNotNull { h ->
                    allChannelList.find { it.id == h.channelId }
                        ?: Channel(
                            id = h.channelId,
                            name = h.channelName,
                            url = h.channelUrl
                        )
                }
            else -> allChannelList.filter { it.group == currentFilter }
        }

        adapter.submit(base)

        // 空状态显示 (加载中不显示空状态)
        binding.emptyView.visibility =
            if (base.isEmpty() && !isLoading) View.VISIBLE else View.GONE

        updateStatus(base.size)
    }

    private fun updateStatus(count: Int) {
        if (isLoading) return
        binding.tvStatus.text = if (searchKeyword != null) {
            getString(R.string.status_no_result) + " ($count)"
        } else {
            "共 $count 个频道"
        }
    }

    private fun launchPlayer(list: List<Channel>, position: Int) {
        if (list.isEmpty()) return
        val ids = list.map { it.id }.toLongArray()
        if (ids.isEmpty()) return
        val pos = position.coerceIn(0, ids.size - 1)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_IDS, ids)
            putExtra(PlayerActivity.EXTRA_POSITION, pos)
        }
        startActivity(intent)
    }

    /**
     * 首次启动后自动进入 CCTV-1 播放
     * 在央视频道中查找 CCTV-1 综合
     */
    private fun autoPlayCctv1() {
        if (allChannelList.isEmpty()) return
        // 优先查找 CCTV-1 综合
        val cctv1 = allChannelList.find {
            it.name.contains("CCTV-1", true) ||
            it.name.contains("CCTV1", true) ||
            it.name.contains("央视一套", true) ||
            it.name.contains("中央一套", true)
        } ?: allChannelList.find {
            it.group == Channel.GROUP_CCTV
        } ?: allChannelList.first()

        val position = allChannelList.indexOf(cctv1)
        launchPlayer(allChannelList, position)
    }

    private fun showSearchDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 30, 60, 10)
        }
        val editText = EditText(this).apply {
            hint = getString(R.string.hint_search)
            setSingleLine(true)
        }
        container.addView(editText)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.action_search)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val keyword = editText.text.toString().trim()
                if (keyword.isNotEmpty()) {
                    searchKeyword = keyword
                    applyFilter()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // 输入即搜索：实时更新结果
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val kw = s?.toString()?.trim().orEmpty()
                searchKeyword = kw.ifEmpty { null }
                applyFilter()
            }
        })

        dialog.show()
        editText.requestFocus()
    }

    private data class Tab(val labelRes: Int, val filterValue: String?)

    companion object {
        const val FILTER_FAVORITES = "favorites"
        const val FILTER_HISTORY = "history"
    }
}
