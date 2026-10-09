package com.example.videoshrink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.ContextCompat

object SmartMediaScanner {
    data class Probe(val bitrate: Int, val mime: String)

    /**
     * Scans every video that Android has granted this app access to.
     *
     * To keep a large library responsive we do a full codec probe for the largest videos first
     * (the files most likely to matter for reclaiming storage). For the remainder, average bitrate
     * is calculated from file size/duration, which is sufficient for the low-benefit filter and
     * target-space planner. No video content leaves the device.
     */
    fun scan(context: Context, options: CompressionOptions): Pair<List<SmartVideoCandidate>, SmartScanSummary> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val raw = mutableListOf<Array<Any>>()
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.SIZE} DESC",
        )?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeI = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val widthI = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightI = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val durationI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val pathI = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val path = c.getString(pathI).orEmpty()
                val name = c.getString(nameI).orEmpty()
                // Never feed our own outputs back into the full-device queue, even if another
                // gallery app moved them out of Movies/YingYa but kept the YingYa output name.
                if (path.contains("YingYa", true) || name.contains("_yingya_", true)) continue
                raw += arrayOf(
                    c.getLong(idI), name, c.getLong(sizeI), c.getInt(widthI),
                    c.getInt(heightI), c.getLong(durationI), path,
                )
            }
        }

        val accessibleCount = raw.size
        val accessibleBytes = raw.sumOf { it[2] as Long }
        val minPct = SettingsStore.smartMinSavingPercent(context)
        val minMb = SettingsStore.smartMinSavingMb(context)
        val processedIndex = ProcessedVideoStore.index(context)

        val candidates = raw.mapIndexedNotNull { index, row ->
            val id = row[0] as Long
            val size = row[2] as Long
            val duration = row[5] as Long
            if (size <= 0 || duration <= 1_000) return@mapIndexedNotNull null
            val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

            // Probe the largest 800 files deeply. For the rest use an accurate container-average
            // bitrate derived from size/duration and avoid thousands of extractor opens.
            val avgBitrate = ((size * 8_000L) / duration)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val probe = if (index < 800) probe(context, uri) else Probe(avgBitrate, "")
            val effectiveBitrate = probe.bitrate.takeIf { it > 0 } ?: avgBitrate

            val displayName = row[1] as String
            val width = row[3] as Int
            val height = row[4] as Int
            val estimate = SmartCompressionEstimator.estimate(
                sizeBytes = size,
                width = width,
                height = height,
                durationMs = duration,
                sourceBitrate = effectiveBitrate,
                sourceMime = probe.mime,
                options = options,
                minSavingPercent = minPct,
                minSavingMb = minMb,
            )
            val previouslyCompressed = processedIndex.contains(
                uri = uri,
                displayName = displayName,
                sizeBytes = size,
                durationMs = duration,
                width = width,
                height = height,
            )
            SmartVideoCandidate(
                uri = uri,
                displayName = displayName,
                sizeBytes = size,
                width = width,
                height = height,
                durationMs = duration,
                bitrate = effectiveBitrate,
                videoMime = probe.mime,
                relativePath = row[6] as String,
                estimatedOutputBytes = estimate.estimatedOutputBytes,
                estimatedSavedBytes = estimate.savedBytes,
                estimatedSavingPercent = estimate.savingPercent,
                recommendable = estimate.recommendable,
                reason = estimate.reason,
                confidencePercent = if (index < 800 && probe.mime.isNotBlank()) 92 else 72,
                previouslyCompressed = previouslyCompressed,
            )
        }.sortedWith(
            compareByDescending<SmartVideoCandidate> { it.recommendable }
                .thenByDescending { it.sizeBytes }
                .thenByDescending { it.estimatedSavedBytes },
        )

        val limited = Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes }.getOrDefault(0L)
        return candidates to SmartScanSummary(
            accessibleVideoCount = accessibleCount,
            scannedVideoCount = candidates.size,
            accessibleVideoBytes = accessibleBytes,
            recommendedCount = candidates.count { it.recommendable && !it.previouslyCompressed },
            processedCount = candidates.count { it.previouslyCompressed },
            reclaimableBytes = candidates.filter { it.recommendable && !it.previouslyCompressed }.sumOf { it.estimatedSavedBytes },
            freeSpaceBytes = free,
            limitedAccess = limited,
        )
    }

    private fun probe(context: Context, uri: Uri): Probe {
        return runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/")) {
                        val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 0
                        return@runCatching Probe(bitrate, mime)
                    }
                }
                Probe(0, "")
            } finally {
                extractor.release()
            }
        }.getOrDefault(Probe(0, ""))
    }
}
