package com.tvlive.app.ui.player

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvlive.app.R
import com.tvlive.app.TvLiveApp
import com.tvlive.app.data.model.Channel
import com.tvlive.app.databinding.ActivityPlayerBinding
import com.tvlive.app.databinding.ItemChannelOverlayBinding
import com.tvlive.app.player.TvPlayerManager
import com.tvlive.app.ui.settings.SettingsActivity
import com.tvlive.app.ui.settings.SourceManagerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 全屏播放界面
 *
 * 功能：
 * - ExoPlayer HLS 直播播放，横屏全屏，占用刘海屏
 * - 遥控器：上下键切台、确认键显示设置面板、左右键频道列表、数字键选台
 * - 手机：点击屏幕显示设置菜单、返回键打开设置面板
 * - 设置面板：切频道、频道列表、收藏、画面比例、刷新源、源管理、设置、退出
 * - 频道信息覆盖层 (4秒自动隐藏)
 * - 加载/错误状态提示与重试
 * - 播放历史记录
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerManager: TvPlayerManager
    private lateinit var overlayAdapter: ChannelOverlayAdapter
    private lateinit var gestureDetector: GestureDetector

    private var channels: List<Channel> = emptyList()
    private var currentIndex = 0

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
    private var aspectIndex = 1  // 默认 FILL, 与 XML resize_mode="fill" 一致

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
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 沉浸式全屏 (隐藏状态栏 + 导航栏)
        hideSystemUI()

        playerManager = TvPlayerManager(this).apply {
            this.onError = this@PlayerActivity.onError
            this.onLoading = this@PlayerActivity.onLoading
            this.onReady = this@PlayerActivity.onReady
        }

        setupChannelList()
        setupRetryButton()
        setupSettingsPanel()
        setupGestureDetector()

        val ids = intent.getLongArrayExtra(EXTRA_CHANNEL_IDS)

        lifecycleScope.launch {
            if (ids == null || ids.isEmpty()) {
                // 直接启动 (从桌面图标), 自动加载并播放 CCTV-1
                loadAndAutoPlay()
            } else {
                // 从频道浏览界面进入
                channels = loadChannels(ids)
                if (channels.isEmpty()) {
                    loadAndAutoPlay()
                } else {
                    currentIndex = intent.getIntExtra(EXTRA_POSITION, 0).coerceIn(0, channels.size - 1)
                    overlayAdapter.submit(channels)
                    playCurrent()
                }
            }
        }

        showHint()
    }

    /**
     * 直接启动时: 加载频道并自动播放 CCTV-1
     * 首次启动若无频道数据, 先刷新直播源
     */
    private suspend fun loadAndAutoPlay() {
        var channelList = TvLiveApp.instance.repository.allChannels.first()

        if (channelList.isEmpty()) {
            // 无频道数据, 需要刷新直播源
            runOnUiThread {
                binding.loadingView.visibility = View.VISIBLE
                binding.errorView.visibility = View.GONE
                binding.tvLoadingText.text = getString(R.string.loading_sources)
            }

            val result = TvLiveApp.instance.repository.refreshAllSources { current, total, name ->
                runOnUiThread {
                    binding.tvLoadingText.text = getString(
                        R.string.status_loading, current, total, name
                    )
                }
            }

            channelList = TvLiveApp.instance.repository.allChannels.first()

            // 标记源已加载
            getSharedPreferences("tvlive_prefs", MODE_PRIVATE)
                .edit().putBoolean("sources_loaded", true).apply()

            if (channelList.isEmpty()) {
                // 显示详细错误信息
                val errorMsg = if (result.errors.isNotEmpty()) {
                    "加载失败: ${result.errors.joinToString("; ").take(200)}\n\n请检查网络后在设置中更换直播源"
                } else {
                    "无法加载频道，请检查网络后重试\n\n提示: 按确认键重试，按菜单键进入设置更换源"
                }
                runOnUiThread {
                    binding.loadingView.visibility = View.GONE
                    binding.errorView.visibility = View.VISIBLE
                    binding.tvErrorText.text = errorMsg
                    binding.btnRetry.requestFocus()
                }
                return
            }
        }

        channels = channelList

        // 优先查找 CCTV-1 综合
        val cctv1 = channels.find {
            it.name.contains("CCTV-1", true) ||
            it.name.contains("CCTV1", true) ||
            it.name.contains("央视一套", true) ||
            it.name.contains("中央一套", true)
        } ?: channels.find { it.group == Channel.GROUP_CCTV } ?: channels.first()

        currentIndex = channels.indexOf(cctv1).coerceAtLeast(0)

        runOnUiThread {
            binding.loadingView.visibility = View.GONE
            binding.errorView.visibility = View.GONE
            overlayAdapter.submit(channels)
            playCurrent()
        }
    }

    private suspend fun loadChannels(ids: LongArray): List<Channel> {
        if (ids.isEmpty()) {
            return TvLiveApp.instance.repository.allChannels.first()
        }
        val idOrder = ids.toList()
        val idSet = ids.toSet()
        return TvLiveApp.instance.repository.allChannels
            .first()
            .filter { it.id in idSet }
            .sortedBy { idOrder.indexOf(it.id) }
    }

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
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = overlayAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupRetryButton() {
        binding.btnRetry.setOnClickListener {
            if (channels.isEmpty()) {
                // 频道为空时重新加载源
                binding.errorView.visibility = View.GONE
                lifecycleScope.launch { loadAndAutoPlay() }
            } else {
                playCurrent()
            }
        }
        // 确保重试按钮可聚焦 (TV 遥控器)
        binding.btnRetry.isFocusable = true
        binding.btnRetry.isFocusableInTouchMode = true
    }

    // ==================== 设置面板 ====================

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
        binding.btnSourceManager.setOnClickListener {
            startActivity(Intent(this, SourceManagerActivity::class.java))
        }
        binding.btnChannelBrowser.setOnClickListener {
            startActivity(Intent(this, com.tvlive.app.ui.main.MainActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnExit.setOnClickListener { finish() }

        // 设置面板按钮焦点处理
        listOf(
            binding.btnPrevChannel, binding.btnNextChannel, binding.btnChannelList,
            binding.btnFavorite, binding.btnAspectRatio, binding.btnRefreshSource,
            binding.btnSourceManager, binding.btnChannelBrowser, binding.btnSettings, binding.btnExit
        ).forEach { view ->
            view.setOnFocusChangeListener { v, hasFocus ->
                v.alpha = if (hasFocus) 1.0f else 0.7f
            }
        }
    }

    private fun showSettingsPanel() {
        isSettingsPanelVisible = true
        binding.settingsPanel.visibility = View.VISIBLE
        // 隐藏错误视图, 确保设置面板可交互
        binding.errorView.visibility = View.GONE
        handler.removeCallbacks(settingsHideRunnable)
        // 更新收藏图标
        val channel = channels.getOrNull(currentIndex)
        binding.ivMenuFavorite.setImageResource(
            if (channel?.favorite == true) R.drawable.ic_star_on else R.drawable.ic_star_off
        )
        // 同步画面比例显示
        binding.tvAspectRatio.text = getString(aspectNames[aspectIndex])
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
                // 错误界面下点击重试加载
                if (binding.errorView.visibility == View.VISIBLE) {
                    binding.errorView.visibility = View.GONE
                    lifecycleScope.launch { loadAndAutoPlay() }
                    return true
                }
                // 点击屏幕显示设置面板
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
        binding.loadingView.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE
        binding.tvLoadingText.text = getString(R.string.action_refresh)
        lifecycleScope.launch {
            val result = TvLiveApp.instance.repository.refreshAllSources()
            channels = TvLiveApp.instance.repository.allChannels.first()
            if (channels.isNotEmpty()) {
                // 刷新后默认回到 CCTV-1
                val cctv1 = channels.find {
                    it.name.contains("CCTV-1", true) ||
                    it.name.contains("CCTV1", true) ||
                    it.name.contains("央视一套", true) ||
                    it.name.contains("中央一套", true)
                } ?: channels.find { it.group == Channel.GROUP_CCTV } ?: channels.first()

                currentIndex = channels.indexOf(cctv1).coerceAtLeast(0)
                overlayAdapter.submit(channels)
                playCurrent()
            } else {
                val errorMsg = if (result.errors.isNotEmpty()) {
                    "刷新失败: ${result.errors.joinToString("; ").take(200)}"
                } else {
                    "未加载到频道，请在直播源管理中更换源"
                }
                binding.tvErrorText.text = errorMsg
                binding.errorView.visibility = View.VISIBLE
                binding.btnRetry.requestFocus()
            }
            binding.loadingView.visibility = View.GONE
        }
    }

    // ==================== 播放控制 ====================

    private fun playCurrent() {
        val channel = channels.getOrNull(currentIndex) ?: return
        updateChannelInfo(channel)
        overlayAdapter.setCurrentIndex(currentIndex)

        binding.loadingView.visibility = View.VISIBLE
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

    // ==================== 频道列表面板 ====================

    private fun toggleChannelList() {
        if (isChannelListVisible) hideChannelList() else showChannelList()
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
    }

    // ==================== 提示 ====================

    private fun showHint() {
        binding.hintView.visibility = View.VISIBLE
        binding.hintView.text = getString(R.string.menu_hint_phone)
        handler.postDelayed(hintHideRunnable, 5000)
    }

    // ==================== 遥控器/按键 ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // 错误界面下: 确认键重试, 菜单键/返回键打开设置面板
            if (binding.errorView.visibility == View.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        binding.errorView.visibility = View.GONE
                        lifecycleScope.launch { loadAndAutoPlay() }
                        return true
                    }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK -> {
                        showSettingsPanel()
                        return true
                    }
                }
            }
            when (event.keyCode) {
                // 上下键: 切换频道 (面板和列表隐藏时)
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
                // 左右键: 显示频道列表 (面板隐藏时)
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isChannelListVisible && !isSettingsPanelVisible) {
                        showChannelList()
                        return true
                    }
                }
                // 确认键: 显示/隐藏设置面板
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
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
                // 返回键: 手机第一次按打开设置面板, 再按退出
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
                            finish()
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
                // 音量键交给系统
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    return super.dispatchKeyEvent(event)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ==================== 生命周期 ====================

    override fun onStart() {
        super.onStart()
        playerManager.resume()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
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
            hideSystemUI()
        }
    }

    /**
     * 沉浸式全屏: 隐藏状态栏和导航栏
     * 兼容旧版 (systemUiVisibility) 和新版 (WindowInsetsController) API
     */
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用新 API
            window.setDecorFitsSystemWindows(false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // Android 10 及以下使用旧 API
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

    companion object {
        const val EXTRA_CHANNEL_IDS = "extra_channel_ids"
        const val EXTRA_POSITION = "extra_position"
    }
}

// ==================== 频道列表覆盖层适配器 ====================

class ChannelOverlayAdapter(
    private val channels: MutableList<Channel> = mutableListOf(),
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<ChannelOverlayAdapter.ViewHolder>() {

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

    class ViewHolder(val binding: ItemChannelOverlayBinding) : RecyclerView.ViewHolder(binding.root)

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
