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

    private var channels: List<Channel> = emptyList()
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
        }

        setupChannelList()
        setupRetryButton()
        setupSettingsPanel()
        setupGestureDetector()
        observeData()

        // 首次启动自动刷新源
        val prefs = getSharedPreferences("tvlive_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("sources_loaded", false)) {
            viewModel.refreshSources()
        } else {
            // 已有缓存数据，直接加载播放
            loadCachedChannelsAndPlay()
        }

        showHint()
    }

    /** 从数据库加载已缓存的频道并自动播放 CCTV-1 */
    private fun loadCachedChannelsAndPlay() {
        lifecycleScope.launch {
            channels = viewModel.allChannels.first()
            if (channels.isNotEmpty()) {
                overlayAdapter.submit(channels)
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
                            channels = list
                            overlayAdapter.submit(channels)
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
                        channels = viewModel.allChannels.first()
                        if (channels.isNotEmpty()) {
                            overlayAdapter.submit(channels)
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
        binding.btnSourceManager.setOnClickListener {
            startActivity(Intent(this, SourceManagerActivity::class.java))
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
     */
    private fun autoPlayCctv1() {
        if (channels.isEmpty()) return
        hasAutoPlayed = true

        val cctv1 = channels.find {
            it.name.contains("CCTV-1", true) ||
            it.name.contains("CCTV1", true) ||
            it.name.contains("央视一套", true) ||
            it.name.contains("中央一套", true)
        } ?: channels.find {
            it.group == Channel.GROUP_CCTV
        } ?: channels.first()

        currentIndex = channels.indexOf(cctv1)
        playCurrent()
    }

    private fun playCurrent() {
        val channel = channels.getOrNull(currentIndex) ?: return
        updateChannelInfo(channel)
        overlayAdapter.setCurrentIndex(currentIndex)

        // 隐藏刷新加载遮罩，显示播放器加载状态
        binding.loadingView.visibility = View.VISIBLE
        binding.tvLoadingText.text = getString(R.string.player_loading)
        binding.errorView.visibility = View.GONE

        playerManager.play(channel.url)
        binding.playerView.player = playerManager.player

        lifecycleScope.launch {
            TvLiveApp.instance.repository.addHistory(channel)
        }
    }

    private fun switchChannel(delta: Int) {
        if (channels.isEmpty()) return
        currentIndex = (currentIndex + delta + channels.size) % channels.size
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
        binding.tvChannelGroup.text = channel.group
        binding.ivFavorite.setImageResource(
            if (channel.favorite) R.drawable.ic_star_on else R.drawable.ic_star_off
        )
        showChannelInfo()
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
                // 左右键: 显示频道列表
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isChannelListVisible && !isSettingsPanelVisible) {
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
