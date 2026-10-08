package com.example.videoshrink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class BatchCompressor(
    private val context: Context,
    private val onItemChanged: (Int) -> Unit,
    private val onRunningChanged: (Boolean) -> Unit,
    private val onAllFinished: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var items: MutableList<VideoItem>? = null
    private var options: CompressionOptions? = null
    private var currentIndex = -1
    private var transformer: Transformer? = null
    private var outputUri: Uri? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private var cancelled = false

    fun start(items: MutableList<VideoItem>, options: CompressionOptions) {
        if (transformer != null) return
        this.items = items
        this.options = options
        this.cancelled = false
        onRunningChanged(true)
        moveToNext()
    }

    fun cancel() {
        cancelled = true
        transformer?.cancel()
        if (currentIndex in 0 until (items?.size ?: 0)) {
            items?.get(currentIndex)?.apply {
                status = VideoStatus.CANCELLED
                error = null
            }
            onItemChanged(currentIndex)
        }
        cleanupFailedOutput()
        transformer = null
        onRunningChanged(false)
    }

    private fun moveToNext() {
        val list = items ?: return finishAll()
        val next = list.indexOfFirst { it.selected && it.status == VideoStatus.READY }
        if (next < 0 || cancelled) {
            finishAll()
            return
        }
        currentIndex = next
        compress(list[next])
    }

    private fun compress(item: VideoItem) {
        val selected = options ?: return finishAll()
        item.status = VideoStatus.COMPRESSING
        item.progress = 0
        item.error = null
        onItemChanged(currentIndex)
        startAttempt(item, selected, attempt = 0, previousError = null)
    }

    private fun startAttempt(
        item: VideoItem,
        selected: CompressionOptions,
        attempt: Int,
        previousError: String?,
    ) {
        if (cancelled) return

        cleanupFailedOutput()

        val created = createOutput(item.displayName)
        if (created == null) {
            item.status = VideoStatus.FAILED
            item.error = "无法创建输出文件"
            onItemChanged(currentIndex)
            moveToNext()
            return
        }

        outputUri = created.first
        outputPfd = created.second
        outputStream = FileOutputStream(created.second.fileDescriptor)

        val inputShortSide = listOf(item.width, item.height)
            .filter { it > 0 }
            .minOrNull()
            ?: selected.targetShortSide

        val effectiveShortSide = when (attempt) {
            0, 1 -> minOf(selected.targetShortSide, inputShortSide)
            else -> when {
                inputShortSide > 720 -> 720
                inputShortSide > 480 -> 480
                else -> inputShortSide
            }
        }.coerceAtLeast(240)

        val useRequestedBitrate = attempt == 0
        val actualCodec = if (attempt == 0) selected.codec else CodecOption.H264
        val videoMime = if (actualCodec == CodecOption.H265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

        val encoderFactory = if (useRequestedBitrate) {
            val bitrateScale = (effectiveShortSide.toDouble() / selected.targetShortSide)
                .coerceAtMost(1.0)
            val presetBitrate = (selected.targetBitrate() * bitrateScale * bitrateScale)
                .toInt()
                .coerceAtLeast(350_000)

            val sourceAverageBitrate = if (item.originalSize > 0 && item.durationMs > 0) {
                ((item.originalSize * 8_000L) / item.durationMs)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            } else {
                Int.MAX_VALUE
            }

            val sourceRatio = when (selected.quality) {
                QualityOption.SPACE -> 0.55
                QualityOption.BALANCED -> 0.72
                QualityOption.HIGH -> 0.86
            }

            val sourceCappedVideoBitrate = if (sourceAverageBitrate == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                (sourceAverageBitrate * sourceRatio)
                    .toInt()
                    .minus(128_000)
                    .coerceAtLeast(350_000)
            }

            val requestedBitrate = minOf(presetBitrate, sourceCappedVideoBitrate)
            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(requestedBitrate)
                .build()

            DefaultEncoderFactory.Builder(context)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .build()
        } else {
            DefaultEncoderFactory.Builder(context)
                .setEnableFallback(true)
                .build()
        }

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                finishOutputSuccess(item)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                val detail = describeExportError(exportException)
                cleanupFailedOutput()
                transformer = null

                if (!cancelled && attempt < 2) {
                    item.progress = 0
                    item.status = VideoStatus.COMPRESSING
                    item.error = detail
                    onItemChanged(currentIndex)
                    handler.post {
                        startAttempt(
                            item = item,
                            selected = selected,
                            attempt = attempt + 1,
                            previousError = detail,
                        )
                    }
                } else {
                    item.status = VideoStatus.FAILED
                    item.progress = 0
                    item.error = buildString {
                        if (!previousError.isNullOrBlank()) append("兼容重试仍失败；")
                        append(detail)
                    }
                    onItemChanged(currentIndex)
                    moveToNext()
                }
            }
        }

        val muxerFactory = MediaStoreMuxerFactory(outputStream!!)
        transformer = Transformer.Builder(context)
            .setVideoMimeType(videoMime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .setMuxerFactory(muxerFactory)
            .addListener(listener)
            .build()

        val effects = Effects(
            emptyList(),
            listOf(
                Presentation.createForShortSide(effectiveShortSide)
                    .copyWithUnsetSideRoundedTo(2),
            ),
        )
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(item.uri))
            .setEffects(effects)
            .build()

        try {
            transformer!!.start(edited, "mediastore-output-${System.nanoTime()}.mp4")
            pollProgress()
        } catch (e: Exception) {
            val detail = describeThrowable(e)
            cleanupFailedOutput()
            transformer = null

            if (!cancelled && attempt < 2) {
                item.progress = 0
                item.status = VideoStatus.COMPRESSING
                item.error = detail
                onItemChanged(currentIndex)
                handler.post {
                    startAttempt(
                        item = item,
                        selected = selected,
                        attempt = attempt + 1,
                        previousError = detail,
                    )
                }
            } else {
                item.status = VideoStatus.FAILED
                item.error = buildString {
                    if (!previousError.isNullOrBlank()) append("兼容重试仍失败；")
                    append(detail)
                }
                onItemChanged(currentIndex)
                moveToNext()
            }
        }
    }

    private fun describeExportError(error: ExportException): String {
        val root = generateSequence(error.cause) { it.cause }.lastOrNull()
        return buildString {
            append("错误码 ")
            append(error.errorCode)
            append("：")
            append(error.message ?: "编码失败")
            if (root != null) {
                append("；")
                append(root.javaClass.simpleName)
                if (!root.message.isNullOrBlank()) {
                    append(": ")
                    append(root.message)
                }
            }
        }
    }

    private fun describeThrowable(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        return buildString {
            append(error.javaClass.simpleName)
            if (!error.message.isNullOrBlank()) {
                append(": ")
                append(error.message)
            }
            if (root !== error) {
                append("；")
                append(root.javaClass.simpleName)
                if (!root.message.isNullOrBlank()) {
                    append(": ")
                    append(root.message)
                }
            }
        }
    }

    private fun pollProgress() {
        val current = transformer ?: return
        val holder = ProgressHolder()
        val state = runCatching { current.getProgress(holder) }.getOrNull()
        if (state == Transformer.PROGRESS_STATE_AVAILABLE && currentIndex >= 0) {
            items?.getOrNull(currentIndex)?.progress = holder.progress.coerceIn(0, 99)
            onItemChanged(currentIndex)
        }
        if (transformer != null) handler.postDelayed({ pollProgress() }, 500)
    }

    private fun finishOutputSuccess(item: VideoItem) {
        val uri = outputUri
        closeOutputHandles()
        if (uri != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
            item.outputUri = uri
            item.outputSize = querySize(uri)
        }
        item.progress = 100
        item.status = VideoStatus.DONE
        item.error = null
        transformer = null
        outputUri = null
        onItemChanged(currentIndex)
        moveToNext()
    }

    private fun finishAll() {
        transformer = null
        closeOutputHandles()
        onRunningChanged(false)
        onAllFinished()
    }

    private fun createOutput(inputName: String): Pair<Uri, android.os.ParcelFileDescriptor>? {
        val base = inputName.substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+"), "_")
            .take(48)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_shrink_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoShrink")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return null
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
        if (pfd == null) {
            context.contentResolver.delete(uri, null, null)
            return null
        }
        return uri to pfd
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Video.Media.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }

    private fun cleanupFailedOutput() {
        val uri = outputUri
        closeOutputHandles()
        if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
        outputUri = null
    }

    private fun closeOutputHandles() {
        runCatching { outputStream?.close() }
        runCatching { outputPfd?.close() }
        outputStream = null
        outputPfd = null
    }
}
