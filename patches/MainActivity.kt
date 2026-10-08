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
import android.view.View
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.videoshrink.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val items get() = CompressionRepository.items
    private lateinit var adapter: VideoAdapter
    private var pendingStartOptions: CompressionOptions? = null
    private var pendingDeleteItems: List<VideoItem> = emptyList()

    private var selectedShortSide: Int = 1080
    private var selectedCodec: CodecOption = CodecOption.H265
    private var selectedQuality: QualityOption = QualityOption.BALANCED

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
        }
    }

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100),
    ) { uris ->
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
        if (added > 0) {
            showSnackbar("已加入 $added 个视频")
        } else {
            showSnackbar("这些视频已经在当前计划中")
        }
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
            showSnackbar("原视频已删除，压缩文件已保留")
        } else {
            showSnackbar("已取消删除，原视频仍保留")
        }
        pendingDeleteItems = emptyList()
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedShortSide = savedInstanceState?.getInt(KEY_SIDE, 1080) ?: 1080
        selectedCodec = runCatching {
            CodecOption.valueOf(savedInstanceState?.getString(KEY_CODEC) ?: CodecOption.H265.name)
        }.getOrDefault(CodecOption.H265)
        selectedQuality = runCatching {
            QualityOption.valueOf(savedInstanceState?.getString(KEY_QUALITY) ?: QualityOption.BALANCED.name)
        }.getOrDefault(QualityOption.BALANCED)

        if (items.isEmpty() && !CompressionRepository.running) {
            JobStore.save(this, emptyList())
        }

        adapter = VideoAdapter(items) { _, _ ->
            JobStore.save(this, items)
            refreshUi()
        }
        binding.videoList.layoutManager = LinearLayoutManager(this)
        binding.videoList.adapter = adapter

        setupPresetControls()
        setupActions()
        updateSettingsSummary()
        refreshUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SIDE, selectedShortSide)
        outState.putString(KEY_CODEC, selectedCodec.name)
        outState.putString(KEY_QUALITY, selectedQuality.name)
        super.onSaveInstanceState(outState)
    }

    private fun setupPresetControls() {
        binding.presetGroup.check(
            when (selectedQuality) {
                QualityOption.SPACE -> R.id.presetSpaceButton
                QualityOption.BALANCED -> R.id.presetBalancedButton
                QualityOption.HIGH -> R.id.presetHighButton
            },
        )
        binding.presetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedQuality = when (checkedId) {
                R.id.presetSpaceButton -> QualityOption.SPACE
                R.id.presetHighButton -> QualityOption.HIGH
                else -> QualityOption.BALANCED
            }
            updatePresetDescription()
            updatePlanMetrics()
            updateStartButtonState()
        }
        updatePresetDescription()
    }

    private fun setupActions() {
        binding.newPlanButton.setOnClickListener { confirmNewPlan() }
        binding.clearListButton.setOnClickListener { confirmClearCurrentList() }

        val openPicker = View.OnClickListener { launchVideoPicker() }
        binding.addButton.setOnClickListener(openPicker)
        binding.emptyAddButton.setOnClickListener(openPicker)

        binding.settingsButton.setOnClickListener {
            if (!CompressionRepository.running) showAdvancedSettings()
        }

        binding.selectAllButton.setOnClickListener {
            if (CompressionRepository.running || items.isEmpty()) return@setOnClickListener
            val shouldSelect = !items.all { it.selected }
            items.forEach { it.selected = shouldSelect }
            JobStore.save(this, items)
            refreshUi()
        }

        binding.removeSelectedButton.setOnClickListener {
            removeSelectedWithUndo()
        }

        binding.startButton.setOnClickListener {
            if (CompressionRepository.running) {
                startService(
                    Intent(this, CompressionService::class.java)
                        .setAction(CompressionService.ACTION_CANCEL),
                )
                return@setOnClickListener
            }

            if (items.isEmpty()) {
                launchVideoPicker()
                return@setOnClickListener
            }

            val selectedItems = items.filter { it.selected }
            if (selectedItems.isEmpty()) {
                showSnackbar("请先勾选要压缩的视频")
                return@setOnClickListener
            }

            selectedItems
                .filter { it.status == VideoStatus.FAILED || it.status == VideoStatus.CANCELLED }
                .forEach {
                    it.status = VideoStatus.READY
                    it.progress = 0
                    it.error = null
                }

            if (selectedItems.none { it.status == VideoStatus.READY }) {
                showSnackbar("所选视频已经处理完成，可新建计划继续")
                return@setOnClickListener
            }

            JobStore.save(this, items)
            requestNotificationAndStart(currentOptions())
        }

        binding.deleteOriginalsButton.setOnClickListener {
            requestDeleteOriginals()
        }
    }

    private fun launchVideoPicker() {
        if (CompressionRepository.running) {
            showSnackbar("压缩进行中，完成或取消后再添加视频")
            return
        }
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
        )
    }

    private fun confirmNewPlan() {
        if (CompressionRepository.running) {
            showSnackbar("压缩进行中，请先完成或取消当前任务")
            return
        }
        if (items.isEmpty()) {
            newPlan(resetSettings = true)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("新建计划？")
            .setMessage("会清空当前列表并恢复推荐设置，但不会删除已经压缩好的文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("新建") { _, _ -> newPlan(resetSettings = true) }
            .show()
    }

    private fun confirmClearCurrentList() {
        if (CompressionRepository.running) {
            showSnackbar("压缩进行中，请先完成或取消当前任务")
            return
        }
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("清空当前列表？")
            .setMessage("只会清空本次计划，不会删除原视频或已经生成的压缩文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                items.clear()
                JobStore.save(this, emptyList())
                refreshUi()
                showSnackbar("当前列表已清空")
            }
            .show()
    }

    private fun removeSelectedWithUndo() {
        if (CompressionRepository.running) return
        val removed = items.withIndex()
            .filter { it.value.selected }
            .map { it.index to it.value }
        if (removed.isEmpty()) {
            showSnackbar("请先勾选要移除的视频")
            return
        }

        items.removeAll { it.selected }
        JobStore.save(this, items)
        refreshUi()

        Snackbar.make(
            binding.rootCoordinator,
            "已从当前计划移除 ${removed.size} 个视频",
            Snackbar.LENGTH_LONG,
        ).setAction("撤销") {
            removed.sortedBy { it.first }.forEach { (index, item) ->
                items.add(index.coerceAtMost(items.size), item)
            }
            JobStore.save(this, items)
            refreshUi()
        }.show()
    }

    private fun newPlan(resetSettings: Boolean) {
        items.clear()
        JobStore.save(this, emptyList())
        if (resetSettings) {
            selectedShortSide = 1080
            selectedCodec = CodecOption.H265
            selectedQuality = QualityOption.BALANCED
            binding.presetGroup.check(R.id.presetBalancedButton)
            updatePresetDescription()
            updateSettingsSummary()
        }
        refreshUi()
        showSnackbar("已创建新的空白计划")
    }

    private fun showAdvancedSettings() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_settings, null)
        dialog.setContentView(view)

        val resolutionGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.resolutionGroup)
        val codecGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.codecGroup)

        resolutionGroup.check(
            when (selectedShortSide) {
                720 -> R.id.resolution720Button
                480 -> R.id.resolution480Button
                else -> R.id.resolution1080Button
            },
        )
        codecGroup.check(
            if (selectedCodec == CodecOption.H264) R.id.codecH264Button else R.id.codecH265Button,
        )

        view.findViewById<View>(R.id.settingsDoneButton).setOnClickListener {
            selectedShortSide = when (resolutionGroup.checkedButtonId) {
                R.id.resolution720Button -> 720
                R.id.resolution480Button -> 480
                else -> 1080
            }
            selectedCodec = if (codecGroup.checkedButtonId == R.id.codecH264Button) {
                CodecOption.H264
            } else {
                CodecOption.H265
            }
            updateSettingsSummary()
            updatePlanMetrics()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateSettingsSummary() {
        val codec = if (selectedCodec == CodecOption.H265) "H.265 / HEVC" else "H.264 / AVC"
        binding.settingsButton.text = "${selectedShortSide}P · $codec     高级设置"
    }

    private fun updatePresetDescription() {
        binding.presetDescriptionText.text = when (selectedQuality) {
            QualityOption.SPACE -> "优先释放空间：适合手机容量紧张、留档视频"
            QualityOption.BALANCED -> "推荐日常使用：体积和清晰度更均衡"
            QualityOption.HIGH -> "优先保留细节：文件会相对更大"
        }
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

    private fun currentOptions(): CompressionOptions = CompressionOptions(
        targetShortSide = selectedShortSide,
        codec = selectedCodec,
        quality = selectedQuality,
    )

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
        showSnackbar("开始本机压缩，切到后台或锁屏也会继续")
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
            showSnackbar("请先等待压缩完成或取消任务")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showSnackbar("批量系统删除确认需要 Android 11 或更高版本")
            return
        }

        val candidates = items.filter { it.status == VideoStatus.DONE && !it.originalDeleted }
        if (candidates.isEmpty()) {
            showSnackbar("没有可删除的已压缩原视频")
            return
        }

        val pairs = candidates.mapNotNull { item ->
            MediaDeletion.toDeletableMediaStoreUri(this, item.uri)?.let { uri -> item to uri }
        }
        if (pairs.isEmpty()) {
            showSnackbar("这些视频来源无法通过系统相册删除确认处理")
            return
        }

        val limited = pairs.take(2000)
        pendingDeleteItems = limited.map { it.first }
        val pendingIntent = MediaStore.createDeleteRequest(contentResolver, limited.map { it.second })
        deleteRequestLauncher.launch(
            IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
        )

        if (pairs.size < candidates.size) {
            showSnackbar("有 ${candidates.size - pairs.size} 个文件不是系统相册媒体，本次不会删除")
        } else if (pairs.size > 2000) {
            showSnackbar("Android 单次最多处理 2000 个，本次先处理前 2000 个")
        }
    }

    private fun refreshUi() {
        adapter.notifyDataSetChanged()
        val running = CompressionRepository.running
        adapter.setSelectionEnabled(!running)

        binding.addButton.isEnabled = !running
        binding.emptyAddButton.isEnabled = !running
        binding.newPlanButton.isEnabled = !running
        binding.clearListButton.isEnabled = !running && items.isNotEmpty()
        binding.selectAllButton.isEnabled = !running && items.isNotEmpty()
        binding.removeSelectedButton.isEnabled = !running && items.any { it.selected }
        binding.settingsButton.isEnabled = !running
        setPresetGroupEnabled(!running)

        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.videoList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.queueActionsScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        refreshSelectionSummary()
        updatePlanMetrics()
        updateStartButtonState()
        updateDeleteButton()
    }

    private fun setPresetGroupEnabled(enabled: Boolean) {
        binding.presetGroup.isEnabled = enabled
        repeat(binding.presetGroup.childCount) { index ->
            binding.presetGroup.getChildAt(index).isEnabled = enabled
        }
    }

    private fun refreshSelectionSummary() {
        val selected = items.count { it.selected }
        binding.selectionText.text = if (items.isEmpty()) {
            "还没有选择视频"
        } else {
            "已选 $selected / ${items.size} 个"
        }
        binding.selectAllButton.text = if (items.isNotEmpty() && items.all { it.selected }) {
            "取消全选"
        } else {
            "全选"
        }
    }

    private fun updateStartButtonState() {
        if (CompressionRepository.running) {
            binding.startButton.isEnabled = true
            binding.startButton.text = "取消压缩"
            return
        }

        if (items.isEmpty()) {
            binding.startButton.isEnabled = true
            binding.startButton.text = "选择视频"
            return
        }

        val selectedItems = items.filter { it.selected }
        if (selectedItems.isEmpty()) {
            binding.startButton.isEnabled = false
            binding.startButton.text = "先勾选要压缩的视频"
            return
        }

        val readyCount = selectedItems.count {
            it.status == VideoStatus.READY || it.status == VideoStatus.FAILED || it.status == VideoStatus.CANCELLED
        }
        if (readyCount > 0) {
            binding.startButton.isEnabled = true
            binding.startButton.text = "压缩 $readyCount 个视频"
        } else {
            binding.startButton.isEnabled = false
            binding.startButton.text = "本批已完成"
        }
    }

    private fun updateDeleteButton() {
        val count = items.count { it.status == VideoStatus.DONE && !it.originalDeleted }
        binding.deleteOriginalsButton.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.deleteOriginalsButton.isEnabled = !CompressionRepository.running && count > 0
        binding.deleteOriginalsButton.text = "系统确认后删除原视频（$count 个）"
    }

    private fun updatePlanMetrics() {
        val selectedItems = items.filter { it.selected }
        val running = CompressionRepository.running

        binding.selectedCountText.text = selectedItems.size.toString()
        binding.planStateText.text = when {
            running -> "正在处理"
            items.isEmpty() -> "等待选择"
            selectedItems.isEmpty() -> "未选择"
            selectedItems.any { it.status == VideoStatus.FAILED } -> "有失败项"
            selectedItems.all { it.status == VideoStatus.DONE } -> "本批完成"
            else -> "准备就绪"
        }

        if (selectedItems.isEmpty()) {
            binding.sourceSizeText.text = "--"
            binding.estimateSizeText.text = "--"
            binding.estimateSavingText.text = "选择视频后会显示预计节省空间"
            binding.bottomEstimateText.text = if (running) {
                "正在本机处理，可以退到后台或锁屏"
            } else {
                "选择视频后显示预计节省空间"
            }
            return
        }

        val sourceBytes = selectedItems.sumOf { it.originalSize.coerceAtLeast(0L) }
        val estimatedBytes = selectedItems.sumOf { estimateOutputBytes(it, currentOptions()) }
        val savedBytes = (sourceBytes - estimatedBytes).coerceAtLeast(0L)
        val savedPercent = if (sourceBytes > 0) {
            ((savedBytes.toDouble() / sourceBytes.toDouble()) * 100.0).toInt().coerceIn(0, 99)
        } else {
            0
        }
        val allDone = selectedItems.all { it.status == VideoStatus.DONE && it.outputSize > 0 }

        binding.sourceSizeText.text = FormatUtils.bytes(sourceBytes)
        binding.estimateSizeText.text = FormatUtils.bytes(estimatedBytes)
        binding.estimateSavingText.text = if (allDone) {
            "实际节省 ${FormatUtils.bytes(savedBytes)}（约 $savedPercent%）"
        } else {
            "预计可节省 ${FormatUtils.bytes(savedBytes)}（约 $savedPercent%） · 实际结果会因视频内容和设备编码器略有差异"
        }
        binding.bottomEstimateText.text = if (running) {
            "正在本机处理，可以退到后台或锁屏"
        } else if (allDone) {
            "本批实际节省 ${FormatUtils.bytes(savedBytes)}"
        } else {
            "预计压缩后 ${FormatUtils.bytes(estimatedBytes)} · 可省约 $savedPercent%"
        }
    }

    private fun estimateOutputBytes(item: VideoItem, options: CompressionOptions): Long {
        if (item.status == VideoStatus.DONE && item.outputSize > 0) return item.outputSize
        if (item.originalSize <= 0 || item.durationMs <= 0) return 0L

        val inputShortSide = listOf(item.width, item.height)
            .filter { it > 0 }
            .minOrNull()
            ?: options.targetShortSide
        val effectiveShortSide = minOf(options.targetShortSide, inputShortSide).coerceAtLeast(240)
        val scale = (effectiveShortSide.toDouble() / options.targetShortSide.toDouble())
            .coerceAtMost(1.0)
        val presetBitrate = (options.targetBitrate() * scale * scale)
            .toInt()
            .coerceAtLeast(350_000)

        val sourceAverageBitrate = ((item.originalSize * 8_000L) / item.durationMs)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val sourceRatio = when (options.quality) {
            QualityOption.SPACE -> 0.55
            QualityOption.BALANCED -> 0.72
            QualityOption.HIGH -> 0.86
        }
        val sourceCappedVideoBitrate = (sourceAverageBitrate * sourceRatio)
            .toInt()
            .minus(128_000)
            .coerceAtLeast(350_000)
        val videoBitrate = minOf(presetBitrate, sourceCappedVideoBitrate)
        val totalBitrate = videoBitrate + 128_000L
        val estimate = (totalBitrate * item.durationMs) / 8_000L
        return estimate.coerceAtMost(item.originalSize)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.rootCoordinator, message, Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_SIDE = "selected_short_side"
        private const val KEY_CODEC = "selected_codec"
        private const val KEY_QUALITY = "selected_quality"
    }
}
