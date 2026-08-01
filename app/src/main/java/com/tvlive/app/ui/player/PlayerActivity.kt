package com.tvlive.app.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvlive.app.R
import com.tvlive.app.TvLiveApp
import com.tvlive.app.data.model.Channel
import com.tvlive.app.databinding.ActivityPlayerBinding
import com.tvlive.app.databinding.ItemChannelOverlayBinding
import com.tvlive.app.player.TvPlayerManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 全屏播放界面
 *
 * 功能：
 * - ExoPlayer HLS 直播播放
 * - 遥控器上下键切台、确认键显示/隐藏频道列表
 * - 数字键直接跳转频道号
 * - 频道信息覆盖层 (4秒自动隐藏)
 * - 左侧频道列表面板 (可上下导航选择)
 * - 加载/错误状态提示与重试
 * - 收藏切换
 * - 播放历史记录
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerManager: TvPlayerManager
    private lateinit var overlayAdapter: ChannelOverlayAdapter

    private var channels: List<Channel> = emptyList()
    private var currentIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private val infoHideRunnable = Runnable { hideChannelInfo() }
    private val hintHideRunnable = Runnable { binding.hintView.visibility = View.GONE }
    private val numberInputRunnable = Runnable { submitNumberInput() }

    private var numberInput = StringBuilder()
    private var isChannelListVisible = false

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

        playerManager = TvPlayerManager(this).apply {
            this.onError = this@PlayerActivity.onError
            this.onLoading = this@PlayerActivity.onLoading
            this.onReady = this@PlayerActivity.onReady
        }

        setupChannelList()
        setupRetryButton()

        val ids = intent.getLongArrayExtra(EXTRA_CHANNEL_IDS) ?: LongArray(0)
        val position = intent.getIntExtra(EXTRA_POSITION, 0)

        lifecycleScope.launch {
            channels = loadChannels(ids)
            if (channels.isEmpty()) {
                finish()
                return@launch
            }
            currentIndex = position.coerceIn(0, channels.size - 1)
            overlayAdapter.submit(channels)
            playCurrent()
        }

        // 显示初始操作提示
        showHint()
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
            playCurrent()
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

        // 记录历史
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
        if (!isChannelListVisible) {
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

        // 聚焦当前频道
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
            // 按频道号查找
            val index = channels.indexOfFirst { it.channelNumber == num }
            if (index >= 0) {
                switchToIndex(index)
            } else {
                // 按序号查找 (1-based)
                if (num <= channels.size) {
                    switchToIndex(num - 1)
                }
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
        // 更新本地状态
        channels = channels.mapIndexed { i, ch ->
            if (i == currentIndex) ch.copy(favorite = newFav) else ch
        }
        binding.ivFavorite.setImageResource(
            if (newFav) R.drawable.ic_star_on else R.drawable.ic_star_off
        )
        overlayAdapter.submit(channels)
        overlayAdapter.setCurrentIndex(currentIndex)
    }

    // ==================== 提示 ====================

    private fun showHint() {
        binding.hintView.visibility = View.VISIBLE
        handler.postDelayed(hintHideRunnable, 5000)
    }

    // ==================== 遥控器按键 ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // 上下键: 切换频道 (频道列表隐藏时)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (!isChannelListVisible) {
                        switchChannel(-1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (!isChannelListVisible) {
                        switchChannel(1)
                        return true
                    }
                }
                // 左右键: 显示频道列表
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isChannelListVisible) {
                        showChannelList()
                        return true
                    }
                }
                // 确认键: 切换频道列表显示/隐藏, 或在错误页重试
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (binding.errorView.visibility == View.VISIBLE) {
                        playCurrent()
                        return true
                    }
                    if (isChannelListVisible) {
                        // 由 RecyclerView 焦点处理选中
                    } else {
                        toggleChannelList()
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
                // 菜单键: 显示频道信息
                KeyEvent.KEYCODE_MENU -> {
                    showChannelInfo()
                    return true
                }
                // 收藏键
                KeyEvent.KEYCODE_FAVORITES, KeyEvent.KEYCODE_BOOKMARK -> {
                    toggleFavorite()
                    return true
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

    override fun onStop() {
        super.onStop()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(infoHideRunnable)
        handler.removeCallbacks(hintHideRunnable)
        handler.removeCallbacks(numberInputRunnable)
        playerManager.release()
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

        // 选中高亮
        b.root.setBackgroundColor(
            if (position == currentIndex) 0x33FF6B35.toInt() else 0x00000000
        )

        b.root.setOnClickListener {
            onSelect(position)
        }
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
