package com.tvlive.app.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tvlive.app.data.model.Source
import com.tvlive.app.databinding.ItemSourceBinding

/**
 * 直播源列表适配器
 *
 * 回调:
 * - onToggle(source, enabled)  启用/禁用切换
 * - onDelete(source)           删除源
 * - onLongClick(source)        长按设为默认
 */
class SourceAdapter(
    private val sources: MutableList<Source> = mutableListOf(),
    private val onToggle: (Source, Boolean) -> Unit,
    private val onDelete: (Source) -> Unit,
    private val onLongClick: (Source) -> Unit
) : RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<Source>) {
        sources.clear()
        sources.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSourceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val source = sources[position]
        val b = holder.binding

        b.tvSourceName.text = source.name
        b.tvChannelCount.text = b.root.context.getString(
            com.tvlive.app.R.string.source_channels, source.channelCount
        )
        b.tvDefault.visibility = if (source.isDefault) View.VISIBLE else View.GONE

        // 先移除监听再设置状态，避免回调循环
        b.swEnabled.setOnCheckedChangeListener(null)
        b.swEnabled.isChecked = source.enabled
        b.swEnabled.setOnCheckedChangeListener { _, isChecked ->
            onToggle(source, isChecked)
        }

        b.btnDelete.setOnClickListener { onDelete(source) }

        b.root.setOnLongClickListener {
            onLongClick(source)
            true
        }

        // 焦点放大
        b.root.setOnFocusChangeListener { _, hasFocus ->
            val scale = if (hasFocus) 1.02f else 1.0f
            b.root.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
    }

    override fun getItemCount(): Int = sources.size
}
