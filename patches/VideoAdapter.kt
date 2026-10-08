package com.example.videoshrink

import android.content.res.ColorStateList
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.videoshrink.databinding.ItemVideoBinding
import java.util.concurrent.Executors

class VideoAdapter(
    private val items: List<VideoItem>,
    private val onSelectionChanged: (VideoItem, Boolean) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private var selectionEnabled: Boolean = true
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)

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
        val context = holder.itemView.context
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

            val key = item.uri.toString()
            thumbnailImage.tag = key
            thumbnailImage.setImageDrawable(null)
            thumbnailExecutor.execute {
                val bitmap = runCatching {
                    context.contentResolver.loadThumbnail(item.uri, Size(240, 240), null)
                }.getOrNull()
                thumbnailImage.post {
                    if (thumbnailImage.tag == key && bitmap != null) {
                        thumbnailImage.setImageBitmap(bitmap)
                    }
                }
            }

            progressBar.visibility = if (item.status == VideoStatus.COMPRESSING) View.VISIBLE else View.GONE
            progressText.visibility = if (item.status == VideoStatus.COMPRESSING) View.VISIBLE else View.GONE
            if (item.status == VideoStatus.COMPRESSING) {
                progressBar.setProgress(item.progress.coerceIn(0, 100), true)
                progressText.text = "${item.progress}%"
            }

            when (item.status) {
                VideoStatus.READY -> {
                    setStatusChip(holder, if (item.selected) "待压缩" else "未选择", StatusKind.NEUTRAL)
                    outputText.visibility = View.GONE
                }
                VideoStatus.COMPRESSING -> {
                    setStatusChip(holder, "压缩中", StatusKind.ACTIVE)
                    outputText.visibility = View.VISIBLE
                    outputText.text = "正在本机处理，可以退到后台或锁屏"
                }
                VideoStatus.DONE -> {
                    setStatusChip(holder, "已完成", StatusKind.SUCCESS)
                    outputText.visibility = View.VISIBLE
                    val deltaText = when {
                        item.originalSize <= 0 || item.outputSize <= 0 -> ""
                        item.outputSize <= item.originalSize -> " · 节省 ${FormatUtils.savedPercent(item.originalSize, item.outputSize)}"
                        else -> {
                            val increased = ((item.outputSize - item.originalSize).toDouble() / item.originalSize * 100.0).toInt()
                            " · 比原片大 ${increased}%"
                        }
                    }
                    val deletedText = if (item.originalDeleted) " · 原片已删除" else ""
                    outputText.text = "压缩后 ${FormatUtils.bytes(item.outputSize)}$deltaText$deletedText"
                }
                VideoStatus.FAILED -> {
                    setStatusChip(holder, "失败", StatusKind.ERROR)
                    outputText.visibility = View.VISIBLE
                    outputText.text = item.error ?: "压缩失败，可重新选择后再试"
                }
                VideoStatus.CANCELLED -> {
                    setStatusChip(holder, "已取消", StatusKind.WARNING)
                    outputText.visibility = View.VISIBLE
                    outputText.text = "可再次勾选并重新开始"
                }
            }
        }
    }

    private enum class StatusKind { NEUTRAL, ACTIVE, SUCCESS, WARNING, ERROR }

    private fun setStatusChip(holder: Holder, text: String, kind: StatusKind) {
        val context = holder.itemView.context
        val (background, foreground) = when (kind) {
            StatusKind.NEUTRAL -> R.color.surface_muted to R.color.text_secondary
            StatusKind.ACTIVE -> R.color.brand_primary_container to R.color.brand_primary
            StatusKind.SUCCESS -> R.color.success_container to R.color.success
            StatusKind.WARNING -> R.color.warning_container to R.color.warning
            StatusKind.ERROR -> R.color.error_container to R.color.error
        }
        holder.binding.statusChip.text = text
        holder.binding.statusChip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, background))
        holder.binding.statusChip.setTextColor(ContextCompat.getColor(context, foreground))
    }
}
