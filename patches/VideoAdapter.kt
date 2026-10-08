package com.example.videoshrink

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.videoshrink.databinding.ItemVideoBinding

class VideoAdapter(
    private val items: List<VideoItem>,
    private val onSelectionChanged: (VideoItem, Boolean) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private var selectionEnabled: Boolean = true

    class Holder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    fun setSelectionEnabled(enabled: Boolean) {
        if (selectionEnabled == enabled) return
        selectionEnabled = enabled
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            selectCheck.setOnCheckedChangeListener(null)
            selectCheck.isChecked = item.selected
            selectCheck.isEnabled = selectionEnabled
            selectCheck.setOnCheckedChangeListener { _, checked ->
                if (item.selected != checked) {
                    item.selected = checked
                    onSelectionChanged(item, checked)
                }
            }

            root.setOnClickListener {
                if (selectionEnabled) selectCheck.isChecked = !selectCheck.isChecked
            }

            nameText.text = item.displayName
            val resolution = if (item.width > 0 && item.height > 0) "${item.width}×${item.height}" else "分辨率未知"
            metaText.text = "${FormatUtils.bytes(item.originalSize)} · $resolution · ${FormatUtils.duration(item.durationMs)}"
            progressBar.progress = item.progress
            statusText.text = when (item.status) {
                VideoStatus.READY -> if (item.selected) "已选择 · 等待压缩" else "未选择"
                VideoStatus.COMPRESSING -> "正在压缩 ${item.progress}%"
                VideoStatus.DONE -> {
                    val deltaText = when {
                        item.originalSize <= 0 || item.outputSize <= 0 -> ""
                        item.outputSize <= item.originalSize -> " · 节省 ${FormatUtils.savedPercent(item.originalSize, item.outputSize)}"
                        else -> {
                            val increased = ((item.outputSize - item.originalSize).toDouble() / item.originalSize * 100.0).toInt()
                            " · 比原片大 ${increased}%"
                        }
                    }
                    val deletedText = if (item.originalDeleted) " · 原片已删除" else ""
                    "完成：${FormatUtils.bytes(item.outputSize)}$deltaText$deletedText"
                }
                VideoStatus.FAILED -> "失败：${item.error ?: "未知错误"}"
                VideoStatus.CANCELLED -> "已取消"
            }
        }
    }
}
