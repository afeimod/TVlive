package com.tvlive.app.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvlive.app.R
import com.tvlive.app.TvLiveApp
import com.tvlive.app.data.model.Channel
import com.tvlive.app.databinding.ActivityMainBinding
import com.tvlive.app.databinding.ItemChannelOverlayBinding
import com.tvlive.app.net.ISPDetector
import com.tvlive.app.player.TvPlayerManager
import com.tvlive.app.ui.RefreshState
import com.tvlive.app.ui.MainViewModel
import com.tvlive.app.ui.settings.SettingsActivity
import com.tvlive.app.ui.settings.SourceManagerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 主界面 - 全屏直播播放器
 *
 * 启动后自动刷新直播源并播放 CCTV-1
 * 点击屏幕或遥控器确认键调出菜单面板
 * 菜单包含：切台、频道列表、收藏、画面比例、刷新源、搜索、源管理、设置、退出
 * 遥控器：上下=切台, 左右=频道列表, 确认=菜单, 数字=选台, 音量键=调音量
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var playerManager: TvPlayerManager
    private lateinit var overlayAdapter: ChannelOverlayAdapter
    private lateinit var gestureDetector: GestureDetector

    private var allChannels: List<Channel> = emptyList()   // 全量频道（所有分组）
    private var channels: List<Channel> = emptyList()      // 当前分组过滤后的频道
    private var channelGroups: List<String> = emptyList()  // 分组名称列表（按APK顺序）
    private var currentGroupIndex = 0                      // 当前选中的分组索引
    private var currentIndex = 0
    private var hasAutoPlayed = false
    private var isRefreshingSources = false

    private val handler = Handler(Looper.getMainLooper())
    private val infoHideRunnable = Runnable { hideChannelInfo() }
    private val hintHideRunnable = Runnable { binding.hintView.visibility = View.GONE }
    private val numberInputRunnable = Runnable { submitNumberInput() }
    private val settingsHideRunnable = Runnable { hideSettingsPanel() }

    private var numberInput = StringBuilder()
    private var isChannelListVisible = false
    private var isSettingsPanelVisible = false

    // 画面比例循环: FIT -> FILL -> 16:9 -> 4:3
    private val aspectModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    )
    private val aspectNames = intArrayOf(
        R.string.menu_aspect_fit,
        R.string.menu_aspect_fill,
        R.string.menu_aspect_16_9,
        R.string.menu_aspect_4_3
    )
    private var aspectIndex = 0

    // 播放器回调
    private val onError: (String) -> Unit = { msg ->
        runOnUiThread {
            binding.loadingView.visibility = View.GONE
            binding.errorView.visibility = View.VISIBLE
            binding.tvErrorText.text = msg
        }
    }
    private val onLoading: () -> Unit = {
        runOnUiThread {
            binding.loadingView.visibility = View.VISIBLE
            binding.errorView.visibility = View.GONE
        }
    }
    private val onReady: () -> Unit = {
        runOnUiThread {
            binding.loadingView.visibility = View.GONE
            binding.errorView.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 真全屏：隐藏状态栏 + 导航栏
        enterImmersiveMode()

        playerManager = TvPlayerManager(this).apply {
            this.onError = this@MainActivity.onError
            this.onLoading = this@MainActivity.onLoading
            this.onReady = this@MainActivity.onReady
            this.onUrlSwitched = { url ->
                runOnUiThread {
                    // URL切换时更新频道信息，显示CDN类型
                    updateUrlLineInfo()
                }
            }
        }

        setupChannelList()
        setupRetryButton()
        setupSettingsPanel()
        setupGestureDetector()
        observeData()

        // ★★★ 先检测ISP，再加载源（对应APK: App.onCreate → 检测ISP类型存入App.f）★★★
        // APK在Application.onCreate中检测ISP，频道数据按ISP过滤
        lifecycleScope.launch {
            ISPDetector.detect(this@MainActivity)
            Log.i("MainActivity", "ISP detected: ${ISPDetector.currentISP.label} (${ISPDetector.currentNetworkType})")

            // ISP检测完成后再加载源
            val prefs = getSharedPreferences("tvlive_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("sources_loaded", false)) {
                viewModel.refreshSources()
            } else {
                loadCachedChannelsAndPlay()
            }
        }

        showHint()
    }

    /** 从数据库加载已缓存的频道并自动播放 CCTV-1 */
    private fun loadCachedChannelsAndPlay() {
        lifecycleScope.launch {
            val list = viewModel.allChannels.first()
            if (list.isNotEmpty()) {
                updateChannelData(list)
                autoPlayCctv1()
            } else {
                // 缓存为空，重新刷新
                viewModel.refreshSources()
            }
        }
    }

    // ==================== 数据观察 ====================

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allChannels.collect { list ->
                        if (list.isNotEmpty()) {
                            updateChannelData(list)
                            if (!hasAutoPlayed) {
                                autoPlayCctv1()
                            }
                        }
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
                isRefreshingSources = false
                // 只有未在播放时才隐藏加载遮罩
                if (!hasAutoPlayed) {
                    binding.loadingView.visibility = View.GONE
                }
            }
            is RefreshState.Loading -> {
                isRefreshingSources = true
                // 只有还没开始播放时才显示刷新加载遮罩
                // 已开始播放后，加载遮罩由播放器回调控制（onLoading/onReady）
                if (!hasAutoPlayed) {
                    binding.loadingView.visibility = View.VISIBLE
                    binding.tvLoadingText.text = getString(
                        R.string.status_loading, state.current, state.total, state.sourceName
                    )
                }
            }
            is RefreshState.Done -> {
                isRefreshingSources = false

                getSharedPreferences("tvlive_prefs", MODE_PRIVATE)
                    .edit().putBoolean("sources_loaded", true).apply()

                if (state.result.totalChannels > 0 && !hasAutoPlayed) {
                    // 重新加载频道列表
                    lifecycleScope.launch {
                        val list = viewModel.allChannels.first()
                        if (list.isNotEmpty()) {
                            updateChannelData(list)
                            autoPlayCctv1()
                        }
                    }
                }

                // 只有未在播放时才显示错误
                if (state.result.failCount > 0 && state.result.successCount == 0 && !hasAutoPlayed) {
                    binding.loadingView.visibility = View.GONE
                    binding.errorView.visibility = View.VISIBLE
                    binding.tvErrorText.text = getString(R.string.status_empty)
                }
            }
        }
    }

    // ==================== 频道数据管理（分组Tab + 分组过滤） ====================

    /**
     * ★★★ 统一更新频道数据（按APK分类体系）★★★
     *
     * 流程：
     * 1. 保存全量频道到 allChannels
     * 2. 按ctype生成分组列表（央视频道 → 卫视频道 → 购物频道 → 超清频道 → 各省份）
     * 3. 构建分组Tab UI
     * 4. 过滤出当前分组的频道并更新列表
     */
    private fun updateChannelData(list: List<Channel>) {
        allChannels = list

        // 按APK ctype生成分组列表（去重+排序）
        val groupOrder = mapOf(
            Channel.GROUP_CCTV to 0,
            Channel.GROUP_SATELLITE to 1,
            Channel.GROUP_SHOPPING to 2,
            Channel.GROUP_UHD to 3,
            Channel.GROUP_LOCAL to 4,
            Channel.GROUP_HK_MACAO_TW to 90,
            Channel.GROUP_INTERNATIONAL to 91,
            Channel.GROUP_OTHER to 95
        )

        channelGroups = allChannels.map { it.group }.distinct().sortedBy { group ->
            groupOrder[group] ?: (5 + (group.firstOrNull()?.code?.rem(80) ?: 0))
        }

        // 如果当前分组不在新分组列表中，重置到第一个分组
        val currentGroup = channelGroups.getOrNull(currentGroupIndex)
        if (currentGroup == null || allChannels.none { it.group == currentGroup }) {
            currentGroupIndex = 0
        }

        // 构建分组Tab UI
        buildGroupTabs()

        // 过滤当前分组的频道
        filterCurrentGroup()
    }

    /**
     * ★★★ 构建分组Tab（完全按APK的type数组顺序）★★★
     *
     * APK Tab: 央视频道 | 卫视频道 | 购物频道 | 超清频道 | 广东 | 湖南 | ...
     * 每个Tab是一个TextView，选中时高亮
     */
    private fun buildGroupTabs() {
        val container = binding.groupTabContainer
        container.removeAllViews()

        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        val dp14 = (14 * resources.displayMetrics.density).toInt()

        channelGroups.forEachIndexed { index, groupName ->
            val tab = android.widget.TextView(this).apply {
                text = groupName
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp8, dp4, dp8, dp4)
                isClickable = true
                isFocusable = true

                // 选中样式
                if (index == currentGroupIndex) {
                    setBackgroundResource(android.R.color.holo_orange_dark)
                    setTextColor(android.graphics.Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    setBackgroundResource(android.R.color.transparent)
                    setTextColor(android.graphics.Color.LTGRAY)
                    setTypeface(null, android.graphics.Typeface.NORMAL)
                }

                setOnClickListener { selectGroup(index) }

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && index != currentGroupIndex) {
                        setTextColor(android.graphics.Color.YELLOW)
                    } else if (index != currentGroupIndex) {
                        setTextColor(android.graphics.Color.LTGRAY)
                    }
                }
            }
            container.addView(tab)

            // Tab间距
            if (index < channelGroups.size - 1) {
                val spacer = android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp14, 1)
                }
                container.addView(spacer)
            }
        }
    }

    /** 选中指定分组Tab，过滤并显示该分组的频道 */
    private fun selectGroup(index: Int) {
        if (index < 0 || index >= channelGroups.size) return
        currentGroupIndex = index
        buildGroupTabs()  // 刷新Tab选中样式
        filterCurrentGroup()

        // 滚动Tab到选中位置可见
        val tabView = binding.groupTabContainer.getChildAt(index * 2)  // 每个tab后面有spacer
        if (tabView != null) {
            binding.groupTabScroller.smoothScrollTo(tabView.left, 0)
        }
    }

    /** 当前正在播放的频道ID（用于分组切换时定位） */
    private var playingChannelId: Long = -1

    /** 过滤当前分组的频道并更新列表 */
    private fun filterCurrentGroup() {
        val groupName = channelGroups.getOrNull(currentGroupIndex) ?: return
        channels = allChannels.filter { it.group == groupName }

        // 频道号按分组内顺序重排
        channels = channels.mapIndexed { i, ch -> ch.copy(channelNumber = i + 1) }

        overlayAdapter.submit(channels)

        // 如果当前正在播放的频道在此分组中，高亮它
        if (playingChannelId >= 0) {
            val idx = channels.indexOfFirst { it.id == playingChannelId }
            currentIndex = if (idx >= 0) idx else 0
        } else {
            currentIndex = 0
        }
        overlayAdapter.setCurrentIndex(currentIndex)
    }

    // ==================== 频道列表覆盖层 ====================

    private fun setupChannelList() {
        overlayAdapter = ChannelOverlayAdapter(
            channels = mutableListOf(),
            onSelect = { position ->
                if (position in channels.indices) {
                    currentIndex = position
                    playCurrent()
                    hideChannelList()
                }
            }
        )
        binding.rvChannelList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = overlayAdapter
            setHasFixedSize(true)
        }
    }

    private fun showChannelList() {
        isChannelListVisible = true
        binding.channelListOverlay.visibility = View.VISIBLE
        binding.channelInfoOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(infoHideRunnable)

        binding.rvChannelList.post {
            overlayAdapter.setCurrentIndex(currentIndex)
            binding.rvChannelList.smoothScrollToPosition(currentIndex)
            val vh = binding.rvChannelList.findViewHolderForAdapterPosition(currentIndex)
            vh?.itemView?.requestFocus()
        }
    }

    private fun hideChannelList() {
        isChannelListVisible = false
        binding.channelListOverlay.visibility = View.GONE
        handler.postDelayed(infoHideRunnable, 4000)
    }

    private fun toggleChannelList() {
        if (isChannelListVisible) hideChannelList() else showChannelList()
    }

    // ==================== 设置菜单面板 ====================

    private fun setupRetryButton() {
        binding.btnRetry.setOnClickListener { playCurrent() }
    }

    private fun setupSettingsPanel() {
        binding.btnPrevChannel.setOnClickListener { switchChannel(-1); hideSettingsPanel() }
        binding.btnNextChannel.setOnClickListener { switchChannel(1); hideSettingsPanel() }
        binding.btnChannelList.setOnClickListener { hideSettingsPanel(); showChannelList() }
        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.btnAspectRatio.setOnClickListener { cycleAspectRatio() }
        binding.btnRefreshSource.setOnClickListener {
            hideSettingsPanel()
            refreshSourcesAndPlay()
        }
        binding.btnSearch.setOnClickListener {
            hideSettingsPanel()
            showSearchDialog()
        }
        // ★★★ URL线路切换按钮（对应APK的遥控器 S1/S2 键 → srcIndex → urlIndex++）★★★
        // 用户可以手动切换当前频道的不同CDN线路
        binding.btnSourceManager.setOnClickListener {
            // 复用源管理按钮：如果在播放中，切换URL线路；否则打开源管理
            val switchedUrl = playerManager.switchUrlLine()
            if (switchedUrl != null) {
                updateUrlLineInfo()
                Toast.makeText(this, "已切换线路", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, SourceManagerActivity::class.java))
            }
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnExit.setOnClickListener { finish() }

        // 设置面板按钮焦点处理
        listOf(
            binding.btnPrevChannel, binding.btnNextChannel, binding.btnChannelList,
            binding.btnFavorite, binding.btnAspectRatio, binding.btnRefreshSource,
            binding.btnSearch, binding.btnSourceManager, binding.btnSettings, binding.btnExit
        ).forEach { view ->
            view.setOnFocusChangeListener { v, hasFocus ->
                v.alpha = if (hasFocus) 1.0f else 0.7f
            }
        }
    }

    private fun showSettingsPanel() {
        isSettingsPanelVisible = true
        binding.settingsPanel.visibility = View.VISIBLE
        handler.removeCallbacks(settingsHideRunnable)
        // 更新收藏图标
        val channel = channels.getOrNull(currentIndex)
        binding.ivMenuFavorite.setImageResource(
            if (channel?.favorite == true) R.drawable.ic_star_on else R.drawable.ic_star_off
        )
        // 聚焦第一个按钮
        binding.btnPrevChannel.requestFocus()
    }

    private fun hideSettingsPanel() {
        isSettingsPanelVisible = false
        binding.settingsPanel.visibility = View.GONE
    }

    private fun toggleSettingsPanel() {
        if (isSettingsPanelVisible) hideSettingsPanel() else showSettingsPanel()
    }

    private fun cycleAspectRatio() {
        aspectIndex = (aspectIndex + 1) % aspectModes.size
        binding.playerView.resizeMode = aspectModes[aspectIndex]
        binding.tvAspectRatio.text = getString(aspectNames[aspectIndex])
    }

    // ==================== 手势/触控 ====================

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 点击屏幕显示菜单
                if (!isSettingsPanelVisible && !isChannelListVisible) {
                    showSettingsPanel()
                } else if (isSettingsPanelVisible) {
                    hideSettingsPanel()
                } else if (isChannelListVisible) {
                    hideChannelList()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击切下一个频道
                switchChannel(1)
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // ==================== 刷新源 ====================

    private fun refreshSourcesAndPlay() {
        // 手动刷新时重置播放状态，允许加载遮罩显示
        hasAutoPlayed = false
        binding.loadingView.visibility = View.VISIBLE
        binding.tvLoadingText.text = getString(R.string.action_refresh)
        binding.errorView.visibility = View.GONE
        viewModel.refreshSources()
    }

    // ==================== 播放控制 ====================

    /**
     * 自动播放 CCTV-1
     * 在频道列表中查找 CCTV-1 综合，找不到则播放第一个央视频道
     * ★★★ 先切换到央视频道分组，再查找 ★★★
     */
    private fun autoPlayCctv1() {
        if (allChannels.isEmpty()) return
        hasAutoPlayed = true

        // 先找到CCTV-1在哪个分组
        val cctv1 = allChannels.find {
            it.name.contains("CCTV-1", true) ||
            it.name.contains("CCTV1", true) ||
            it.name.contains("央视一套", true) ||
            it.name.contains("中央一套", true)
        } ?: allChannels.find {
            it.group == Channel.GROUP_CCTV
        } ?: allChannels.first()

        // 切换到CCTV-1所在分组
        val targetGroupIndex = channelGroups.indexOf(cctv1.group)
        if (targetGroupIndex >= 0) {
            currentGroupIndex = targetGroupIndex
            buildGroupTabs()
            filterCurrentGroup()
        }

        currentIndex = channels.indexOfFirst { it.id == cctv1.id }.coerceAtLeast(0)
        playCurrent()
    }

    private fun playCurrent() {
        val channel = channels.getOrNull(currentIndex) ?: return
        playingChannelId = channel.id
        updateChannelInfo(channel)
        overlayAdapter.setCurrentIndex(currentIndex)

        // 隐藏刷新加载遮罩，显示播放器加载状态
        binding.loadingView.visibility = View.VISIBLE
        binding.tvLoadingText.text = getString(R.string.player_loading)
        binding.errorView.visibility = View.GONE

        // ★★★ 传入主 URL + 备用 URL 列表，播放器按APK逻辑自动降级 ★★★
        // 播放流程（与APK完全一致）：
        // 1. 清DNS缓存（dns_cache_clear=1）
        // 2. 按ISP过滤URL（$Y/$D/$L标签）
        // 3. 按CDN优先级排序（咪咕 > sys_ > 腾讯 > 抖音）
        // 4. 协议解析（sys_ → 系统播放器，ikkHeaders → 注入Headers）
        // 5. 播放失败 → urlIndex+1（对应APK的reconnect=3）
        playerManager.play(channel.url, channel.getBackupUrlList())
        binding.playerView.player = playerManager.player

        // 显示URL线路信息
        updateUrlLineInfo()

        lifecycleScope.launch {
            TvLiveApp.instance.repository.addHistory(channel)
        }
    }

    /**
     * ★★★ 切换频道（按APK逻辑：分组内循环，到边界自动切换到下一分组）★★★
     *
     * APK行为：到达分组末尾 → 进入下一个分组第一个频道
     */
    private fun switchChannel(delta: Int) {
        if (allChannels.isEmpty()) return

        val newIndex = currentIndex + delta
        if (newIndex in channels.indices) {
            // 分组内切换
            currentIndex = newIndex
        } else {
            // 到达分组边界 → 切换到下一/上一分组
            val nextGroupIndex = currentGroupIndex + delta
            if (nextGroupIndex in channelGroups.indices) {
                selectGroup(nextGroupIndex)
                currentIndex = if (delta > 0) 0 else (channels.size - 1).coerceAtLeast(0)
            } else {
                // 循环：最后一组 → 第一组，反之亦然
                val wrapGroup = if (delta > 0) 0 else channelGroups.size - 1
                selectGroup(wrapGroup)
                currentIndex = if (delta > 0) 0 else (channels.size - 1).coerceAtLeast(0)
            }
        }
        playCurrent()
    }

    private fun switchToIndex(index: Int) {
        if (index !in channels.indices) return
        currentIndex = index
        playCurrent()
    }

    // ==================== 频道信息覆盖层 ====================

    private fun updateChannelInfo(channel: Channel) {
        binding.tvChannelNumber.text = if (channel.channelNumber > 0) {
            channel.channelNumber.toString()
        } else {
            "${currentIndex + 1}"
        }
        binding.tvChannelName.text = channel.name
        // ★ 显示分组 + ISP信息（对应APK: 分类列表显示当前ISP类型）
        val ispInfo = ISPDetector.currentISP.label
        binding.tvChannelGroup.text = if (ispInfo != "未知") "${channel.group} · $ispInfo" else channel.group
        binding.ivFavorite.setImageResource(
            if (channel.favorite) R.drawable.ic_star_on else R.drawable.ic_star_off
        )
        showChannelInfo()
    }

    /** 更新URL线路信息（对应APK: 频道信息栏显示当前线路/CDN类型） */
    private fun updateUrlLineInfo() {
        val urlInfo = playerManager.getCurrentUrlInfo()
        if (urlInfo.isNotBlank()) {
            // 在频道名称后显示线路信息
            val channel = channels.getOrNull(currentIndex)
            if (channel != null) {
                binding.tvChannelName.text = "${channel.name} $urlInfo"
            }
        }
    }

    private fun showChannelInfo() {
        binding.channelInfoOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(infoHideRunnable)
        handler.postDelayed(infoHideRunnable, 4000)
    }

    private fun hideChannelInfo() {
        if (!isChannelListVisible && !isSettingsPanelVisible) {
            binding.channelInfoOverlay.visibility = View.GONE
        }
    }

    // ==================== 数字键输入 ====================

    private fun onNumberKey(digit: Int) {
        numberInput.append(digit.toString())
        binding.tvNumberInput.visibility = View.VISIBLE
        binding.tvNumberInput.text = numberInput.toString()
        handler.removeCallbacks(numberInputRunnable)
        handler.postDelayed(numberInputRunnable, 1500)
    }

    private fun submitNumberInput() {
        if (numberInput.isEmpty()) return
        val num = numberInput.toString().toIntOrNull()
        binding.tvNumberInput.visibility = View.GONE
        numberInput.clear()

        if (num != null && num > 0) {
            val index = channels.indexOfFirst { it.channelNumber == num }
            if (index >= 0) {
                switchToIndex(index)
            } else if (num <= channels.size) {
                switchToIndex(num - 1)
            }
        }
    }

    // ==================== 收藏 ====================

    private fun toggleFavorite() {
        val channel = channels.getOrNull(currentIndex) ?: return
        val newFav = !channel.favorite
        lifecycleScope.launch {
            TvLiveApp.instance.repository.setFavorite(channel, newFav)
        }
        allChannels = allChannels.map { ch ->
            if (ch.id == channel.id) ch.copy(favorite = newFav) else ch
        }
        channels = channels.mapIndexed { i, ch ->
            if (i == currentIndex) ch.copy(favorite = newFav) else ch
        }
        binding.ivFavorite.setImageResource(
            if (newFav) R.drawable.ic_star_on else R.drawable.ic_star_off
        )
        binding.ivMenuFavorite.setImageResource(
            if (newFav) R.drawable.ic_star_on else R.drawable.ic_star_off
        )
        overlayAdapter.submit(channels)
        overlayAdapter.setCurrentIndex(currentIndex)
        Toast.makeText(
            this,
            if (newFav) R.string.action_favorite else R.string.action_unfavorite,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ==================== 搜索 ====================

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
                    searchAndPlay(keyword)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
        editText.requestFocus()
    }

    private fun searchAndPlay(keyword: String) {
        val found = channels.find { it.name.contains(keyword, ignoreCase = true) }
        if (found != null) {
            currentIndex = channels.indexOf(found)
            playCurrent()
        } else {
            Toast.makeText(this, R.string.status_no_result, Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 提示 ====================

    private fun showHint() {
        binding.hintView.visibility = View.VISIBLE
        binding.hintView.text = getString(R.string.menu_hint_tv)
        handler.postDelayed(hintHideRunnable, 5000)
    }

    // ==================== 遥控器/按键 ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // 上下键: 切换频道
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (!isChannelListVisible && !isSettingsPanelVisible) {
                        switchChannel(-1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (!isChannelListVisible && !isSettingsPanelVisible) {
                        switchChannel(1)
                        return true
                    }
                }
                // ★★★ 左右键: 切换分组Tab（频道列表打开时）或显示频道列表 ★★★
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isChannelListVisible) {
                        // 频道列表打开时：左键切换到上一分组
                        if (currentGroupIndex > 0) {
                            selectGroup(currentGroupIndex - 1)
                        }
                        return true
                    } else if (!isSettingsPanelVisible) {
                        showChannelList()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isChannelListVisible) {
                        // 频道列表打开时：右键切换到下一分组
                        if (currentGroupIndex < channelGroups.size - 1) {
                            selectGroup(currentGroupIndex + 1)
                        }
                        return true
                    } else if (!isSettingsPanelVisible) {
                        showChannelList()
                        return true
                    }
                }
                // 确认键: 显示/隐藏设置面板
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (binding.errorView.visibility == View.VISIBLE) {
                        playCurrent()
                        return true
                    }
                    if (isChannelListVisible) {
                        // 由 RecyclerView 焦点处理选中
                    } else if (isSettingsPanelVisible) {
                        // 由设置面板按钮处理
                    } else {
                        toggleSettingsPanel()
                        return true
                    }
                }
                // 数字键
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
                KeyEvent.KEYCODE_9 -> {
                    val digit = event.keyCode - KeyEvent.KEYCODE_0
                    onNumberKey(digit)
                    return true
                }
                // 返回键
                KeyEvent.KEYCODE_BACK -> {
                    when {
                        binding.tvNumberInput.visibility == View.VISIBLE -> {
                            handler.removeCallbacks(numberInputRunnable)
                            numberInput.clear()
                            binding.tvNumberInput.visibility = View.GONE
                            return true
                        }
                        isSettingsPanelVisible -> {
                            hideSettingsPanel()
                            return true
                        }
                        isChannelListVisible -> {
                            hideChannelList()
                            return true
                        }
                        binding.channelInfoOverlay.visibility == View.VISIBLE -> {
                            hideChannelInfo()
                            return true
                        }
                        else -> {
                            showSettingsPanel()
                            return true
                        }
                    }
                }
                // 菜单键: 显示设置面板
                KeyEvent.KEYCODE_MENU -> {
                    toggleSettingsPanel()
                    return true
                }
                // 收藏键
                KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_STAR -> {
                    toggleFavorite()
                    return true
                }
                // 音量键 - 交给 TvPlayerManager 处理，确保电视声音输出
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    playerManager.volumeUp()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    playerManager.volumeDown()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    playerManager.toggleMute()
                    return true
                }
                // ★★★ R键: 手动切换URL线路（对应APK的遥控器S1/S2键 → srcIndex → urlIndex++）★★★
                KeyEvent.KEYCODE_R -> {
                    val switchedUrl = playerManager.switchUrlLine()
                    if (switchedUrl != null) {
                        updateUrlLineInfo()
                        Toast.makeText(this, "已切换线路", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "当前频道仅有一个线路", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ==================== 生命周期 ====================

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        playerManager.resume()
    }

    override fun onStop() {
        super.onStop()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(infoHideRunnable)
        handler.removeCallbacks(hintHideRunnable)
        handler.removeCallbacks(numberInputRunnable)
        handler.removeCallbacks(settingsHideRunnable)
        playerManager.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveMode()
        }
    }

    /**
     * 进入真全屏沉浸模式
     * 隐藏状态栏 + 导航栏，滑动边缘可临时显示
     * 兼容 Android 5.0 (API 21) 到 Android 14+
     */
    private fun enterImmersiveMode() {
        // 额外兼容：使用 WindowManager flags
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // 让内容延伸到状态栏和导航栏后面
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 使用 WindowInsetsController 隐藏系统栏（兼容新旧 API）
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 同时设置旧版 flag 作为兼容（API < 30）
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }
}

// ==================== 频道列表覆盖层适配器 ====================

class ChannelOverlayAdapter(
    private val channels: MutableList<Channel> = mutableListOf(),
    private val onSelect: (Int) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<ChannelOverlayAdapter.ViewHolder>() {

    private var currentIndex = -1

    fun submit(list: List<Channel>) {
        channels.clear()
        channels.addAll(list)
        notifyDataSetChanged()
    }

    fun setCurrentIndex(index: Int) {
        val old = currentIndex
        currentIndex = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }

    class ViewHolder(val binding: ItemChannelOverlayBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelOverlayBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        val b = holder.binding

        b.tvChannelNumber.text = if (channel.channelNumber > 0) {
            channel.channelNumber.toString()
        } else {
            (position + 1).toString()
        }
        b.tvChannelName.text = channel.name
        b.ivFavorite.visibility = if (channel.favorite) View.VISIBLE else View.GONE

        b.root.setBackgroundColor(
            if (position == currentIndex) 0x33FF6B35.toInt() else 0x00000000
        )

        b.root.setOnClickListener { onSelect(position) }
        b.root.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(
                when {
                    hasFocus -> 0x660066CC.toInt()
                    position == currentIndex -> 0x33FF6B35.toInt()
                    else -> 0x00000000
                }
            )
        }
        b.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                onSelect(position)
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int = channels.size
}
