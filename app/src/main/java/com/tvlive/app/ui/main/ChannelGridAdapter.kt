package com.tvlive.app.ui.main

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvlive.app.R
import com.tvlive.app.data.model.Channel
import com.tvlive.app.databinding.ItemChannelGridBinding

/**
 * 频道网格适配器
 *
 * - 支持焦点放大效果 (scale 1.0 -> 1.05)
 * - 点击回调 (channel, position, channelList)
 * - 长按收藏 / 取消收藏
 * - 根据 group 显示不同颜色标签
 */
class ChannelGridAdapter(
    private val channels: MutableList<Channel> = mutableListOf(),
    private val onClick: (Channel, Int, List<Channel>) -> Unit,
    private val onLongClick: (Channel, Int) -> Unit
) : RecyclerView.Adapter<ChannelGridAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemChannelGridBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<Channel>) {
        channels.clear()
        channels.addAll(list)
        notifyDataSetChanged()
    }

    /** 返回当前列表的快照，用于向播放器传递频道列表 */
    fun getChannels(): List<Channel> = channels.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        val b = holder.binding
        val context = b.root.context

        b.tvChannelName.text = channel.name
        b.tvNumber.text = if (channel.channelNumber > 0) channel.channelNumber.toString() else ""
        bindGroupTag(context, b, channel.group)
        b.ivFavorite.visibility = if (channel.favorite) android.view.View.VISIBLE else android.view.View.GONE
        bindLogo(context, b, channel)

        // 焦点放大效果
        b.root.setOnFocusChangeListener { _, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1.0f
            b.root.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            b.root.z = if (hasFocus) 8f else 0f
        }

        b.root.setOnClickListener {
            onClick(channel, holder.bindingAdapterPosition, channels.toList())
        }
        b.root.setOnLongClickListener {
            onLongClick(channel, holder.bindingAdapterPosition)
            true
        }
    }

    private fun bindLogo(context: Context, b: ItemChannelGridBinding, channel: Channel) {
        if (!channel.logo.isNullOrBlank()) {
            b.ivLogo.visibility = android.view.View.VISIBLE
            b.tvLogoText.visibility = android.view.View.GONE
            Glide.with(context)
                .load(channel.logo)
                .placeholder(R.drawable.ic_tv_logo)
                .error(R.drawable.ic_tv_logo)
                .into(b.ivLogo)
        } else {
            b.ivLogo.visibility = android.view.View.GONE
            b.tvLogoText.visibility = android.view.View.VISIBLE
            b.tvLogoText.text = channel.name.take(1)
        }
    }

    private fun bindGroupTag(context: Context, b: ItemChannelGridBinding, group: String) {
        b.tvGroup.text = group
        val color = when (group) {
            Channel.GROUP_CCTV -> R.color.tag_cctv
            Channel.GROUP_SATELLITE -> R.color.tag_satellite
            Channel.GROUP_LOCAL -> R.color.tag_local
            Channel.GROUP_HK_MACAO_TW -> R.color.tag_hk
            Channel.GROUP_INTERNATIONAL -> R.color.tag_international
            else -> R.color.tag_other
        }
        b.tvGroup.setBackgroundColor(ContextCompat.getColor(context, color))
    }

    override fun getItemCount(): Int = channels.size
}
