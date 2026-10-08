package com.example.videoshrink

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.videoshrink.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val items get() = CompressionRepository.items
    private lateinit var adapter: VideoAdapter
    private var pendingStartOptions: CompressionOptions? = null
    private var pendingDeleteItems: List<VideoItem> = emptyList()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        var added = 0
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val existing = items.firstOrNull { it.uri == uri }
            if (existing == null) {
                items += VideoMetadata.read(this, uri).copy(selected = true)
                added++
            } else {
                existing.selected = true
            }
        }
        JobStore.save(this, items)
        refreshUi()
        if (added > 0) Toast.makeText(this, "已加入 $added 个视频", Toast.LENGTH_SHORT).show()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingStartOptions?.let { options ->
            pendingStartOptions = null
            launchCompression(options)
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDeleteItems.forEach { it.originalDeleted = true }
            JobStore.save(this, items)
            Toast.makeText(this, "原视频已删除", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "已取消删除，原视频仍保留", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteItems = emptyList()
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Cold launches start with a clean current plan. Old completed rows are not restored.
        if (items.isEmpty() && !CompressionRepository.running) JobStore.save(this, emptyList())

        adapter = VideoAdapter(items) { _, _ ->
            JobStore.save(this, items)
            refreshSelectionSummary()
            updateStartButtonState()
        }
        binding.videoList.layoutManager = LinearLayoutManager(this)
        binding.videoList.adapter = adapter

        setupSpinners()
        setupActions()
        refreshUi()
    }

    private fun setupActions() {
        binding.newPlanButton.setOnClickListener { newPlan(resetSettings = true) }
        binding.clearListButton.setOnClickListener { clearCurrentList() }

        binding.addButton.setOnClickListener {
            if (!CompressionRepository.running) picker.launch(arrayOf("video/*"))
        }

        binding.selectAllButton.setOnClickListener {
            if (CompressionRepository.running) return@setOnClickListener
            items.forEach { it.selected = true }
            JobStore.save(this, items)
            refreshUi()
        }

        binding.deselectAllButton.setOnClickListener {
            if (CompressionRepository.running) return@setOnClickListener
            items.forEach { it.selected = false }
            JobStore.save(this, items)
            refreshUi()
        }

        binding.removeSelectedButton.setOnClickListener {
            if (CompressionRepository.running) return@setOnClickListener
            val count = items.count { it.selected }
            if (count == 0) {
                Toast.makeText(this, "请先勾选要移除的视频", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            items.removeAll { it.selected }
            JobStore.save(this, items)
            refreshUi()
            Toast.makeText(this, "已从当前计划移除 $count 个视频", Toast.LENGTH_SHORT).show()
        }

        binding.startButton.setOnClickListener {
            if (CompressionRepository.running) {
                startService(Intent(this, CompressionService::class.java).setAction(CompressionService.ACTION_CANCEL))
                return@setOnClickListener
            }
            if (items.isEmpty()) {
                Toast.makeText(this, "请先选择视频", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedItems = items.filter { it.selected }
            if (selectedItems.isEmpty()) {
                Toast.makeText(this, "请先勾选要压缩的视频", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            selectedItems.filter { it.status == VideoStatus.FAILED || it.status == VideoStatus.CANCELLED }.forEach {
                it.status = VideoStatus.READY
                it.progress = 0
                it.error = null
            }
            if (selectedItems.none { it.status == VideoStatus.READY }) {
                Toast.makeText(this, "所选视频没有待压缩项目，可新建计划后重新选择", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            JobStore.save(this, items)
            requestNotificationAndStart(currentOptions())
        }

        binding.deleteOriginalsButton.setOnClickListener {
            requestDeleteOriginals()
        }
    }

    private fun newPlan(resetSettings: Boolean) {
        if (CompressionRepository.running) {
            Toast.makeText(this, "压缩进行中，请先完成或取消当前任务", Toast.LENGTH_SHORT).show()
            return
        }
        items.clear()
        JobStore.save(this, emptyList())
        if (resetSettings) {
            binding.resolutionSpinner.setSelection(0)
            binding.codecSpinner.setSelection(0)
            binding.qualitySpinner.setSelection(1)
        }
        refreshUi()
        Toast.makeText(this, "已新建空白计划，已压缩文件不会被删除", Toast.LENGTH_SHORT).show()
    }

    private fun clearCurrentList() {
        if (CompressionRepository.running) {
            Toast.makeText(this, "压缩进行中，请先完成或取消当前任务", Toast.LENGTH_SHORT).show()
            return
        }
        if (items.isEmpty()) return
        items.clear()
        JobStore.save(this, emptyList())
        refreshUi()
        Toast.makeText(this, "当前列表已清空", Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(CompressionService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshUi()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onStop()
    }

    private fun setupSpinners() {
        binding.resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("1080P（推荐）", "720P（更省空间）", "480P（最省空间）"),
        )
        binding.codecSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            CodecOption.entries.map { it.label },
        )
        binding.qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            QualityOption.entries.map { it.label },
        )
        binding.codecSpinner.setSelection(0)
        binding.qualitySpinner.setSelection(1)
    }

    private fun currentOptions(): CompressionOptions {
        val side = when (binding.resolutionSpinner.selectedItemPosition) {
            1 -> 720
            2 -> 480
            else -> 1080
        }
        val codec = CodecOption.entries[binding.codecSpinner.selectedItemPosition.coerceIn(0, CodecOption.entries.lastIndex)]
        val quality = QualityOption.entries[binding.qualitySpinner.selectedItemPosition.coerceIn(0, QualityOption.entries.lastIndex)]
        return CompressionOptions(side, codec, quality)
    }

    private fun requestNotificationAndStart(options: CompressionOptions) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartOptions = options
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchCompression(options)
        }
    }

    private fun launchCompression(options: CompressionOptions) {
        CompressionRepository.running = true
        refreshUi()
        val intent = Intent(this, CompressionService::class.java).apply {
            action = CompressionService.ACTION_START
            putExtra(CompressionService.EXTRA_SIDE, options.targetShortSide)
            putExtra(CompressionService.EXTRA_CODEC, options.codec.name)
            putExtra(CompressionService.EXTRA_QUALITY, options.quality.name)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestDeleteOriginals() {
        if (CompressionRepository.running) {
            Toast.makeText(this, "请先等待压缩完成或取消任务", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "批量系统删除确认需要 Android 11 或更高版本", Toast.LENGTH_LONG).show()
            return
        }

        val candidates = items.filter { it.status == VideoStatus.DONE && !it.originalDeleted }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "没有可删除的已压缩原视频", Toast.LENGTH_SHORT).show()
            return
        }

        val pairs = candidates.mapNotNull { item ->
            MediaDeletion.toDeletableMediaStoreUri(this, item.uri)?.let { uri -> item to uri }
        }
        if (pairs.isEmpty()) {
            Toast.makeText(this, "这些视频来源无法通过系统相册删除确认处理", Toast.LENGTH_LONG).show()
            return
        }

        val limited = pairs.take(2000)
        pendingDeleteItems = limited.map { it.first }
        val pendingIntent = MediaStore.createDeleteRequest(contentResolver, limited.map { it.second })
        val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        deleteRequestLauncher.launch(request)

        if (pairs.size < candidates.size) {
            Toast.makeText(this, "有 ${candidates.size - pairs.size} 个文件不是系统相册媒体，未加入本次删除", Toast.LENGTH_LONG).show()
        } else if (pairs.size > 2000) {
            Toast.makeText(this, "Android 单次最多处理 2000 个，本次先删除前 2000 个", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshUi() {
        adapter.notifyDataSetChanged()
        val running = CompressionRepository.running
        adapter.setSelectionEnabled(!running)
        binding.addButton.isEnabled = !running
        binding.newPlanButton.isEnabled = !running
        binding.clearListButton.isEnabled = !running && items.isNotEmpty()
        binding.selectAllButton.isEnabled = !running && items.isNotEmpty()
        binding.deselectAllButton.isEnabled = !running && items.isNotEmpty()
        binding.removeSelectedButton.isEnabled = !running && items.any { it.selected }
        binding.resolutionSpinner.isEnabled = !running
        binding.codecSpinner.isEnabled = !running
        binding.qualitySpinner.isEnabled = !running
        binding.startButton.text = if (running) "取消任务" else "开始压缩"
        updateSummary()
        refreshSelectionSummary()
        updateStartButtonState()
        updateDeleteButton()
    }

    private fun updateStartButtonState() {
        if (CompressionRepository.running) {
            binding.startButton.isEnabled = true
            return
        }
        binding.startButton.isEnabled = items.any {
            it.selected && (it.status == VideoStatus.READY || it.status == VideoStatus.FAILED || it.status == VideoStatus.CANCELLED)
        }
    }

    private fun refreshSelectionSummary() {
        val selected = items.count { it.selected }
        binding.selectionText.text = if (items.isEmpty()) {
            "当前计划暂无视频"
        } else {
            "已选 $selected / ${items.size} 个"
        }
        binding.removeSelectedButton.isEnabled = !CompressionRepository.running && selected > 0
    }

    private fun updateDeleteButton() {
        val count = items.count { it.status == VideoStatus.DONE && !it.originalDeleted }
        binding.deleteOriginalsButton.isEnabled = !CompressionRepository.running && count > 0
        binding.deleteOriginalsButton.text = if (count > 0) {
            "系统确认后删除原视频（$count 个）"
        } else {
            "删除已压缩原视频"
        }
    }

    private fun updateSummary() {
        if (items.isEmpty()) {
            binding.summaryText.text = "新计划 · 尚未选择视频"
            return
        }
        val original = items.filterNot { it.originalDeleted }.sumOf { it.originalSize.coerceAtLeast(0) }
        val completed = items.filter { it.status == VideoStatus.DONE }
        val completedOriginal = completed.sumOf { it.originalSize.coerceAtLeast(0) }
        val output = completed.sumOf { it.outputSize.coerceAtLeast(0) }
        val done = completed.size
        val failed = items.count { it.status == VideoStatus.FAILED }
        val deleted = items.count { it.originalDeleted }
        val saved = (completedOriginal - output).coerceAtLeast(0L)
        binding.summaryText.text = buildString {
            append("当前计划 ${items.size} 个 · 原片 ${FormatUtils.bytes(original)}")
            if (done > 0) append(" · 已完成 $done 个")
            if (saved > 0) append(" · 节省 ${FormatUtils.bytes(saved)}")
            if (deleted > 0) append(" · 已删原片 $deleted 个")
            if (failed > 0) append(" · 失败 $failed 个")
        }
    }
}
