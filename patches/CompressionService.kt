package com.example.videoshrink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class CompressionService : Service() {
    private var compressor: BatchCompressor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelAndStop()
            ACTION_START -> startCompression(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCompression(intent: Intent) {
        if (compressor != null) return
        if (CompressionRepository.items.isEmpty()) {
            stopSelf()
            return
        }

        val options = CompressionOptions(
            targetShortSide = intent.getIntExtra(EXTRA_SIDE, 1080),
            codec = runCatching { CodecOption.valueOf(intent.getStringExtra(EXTRA_CODEC) ?: CodecOption.H265.name) }
                .getOrDefault(CodecOption.H265),
            quality = runCatching { QualityOption.valueOf(intent.getStringExtra(EXTRA_QUALITY) ?: QualityOption.BALANCED.name) }
                .getOrDefault(QualityOption.BALANCED),
        )

        startAsForeground("准备批量压缩…", 0, 0)
        acquireWakeLock()

        compressor = BatchCompressor(
            context = applicationContext,
            onItemChanged = { index ->
                JobStore.save(applicationContext, CompressionRepository.items)
                broadcastState()
                updateNotification(index)
            },
            onRunningChanged = { isRunning ->
                CompressionRepository.running = isRunning
                JobStore.save(applicationContext, CompressionRepository.items)
                broadcastState()
            },
            onAllFinished = {
                val done = CompressionRepository.items.count { it.selected && it.status == VideoStatus.DONE }
                JobStore.save(applicationContext, CompressionRepository.items)
                broadcastState()
                releaseWakeLock()
                showFinishedNotification(done)
                compressor = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
        )
        compressor!!.start(CompressionRepository.items, options)
    }

    private fun cancelAndStop() {
        compressor?.cancel()
        compressor = null
        CompressionRepository.running = false
        JobStore.save(applicationContext, CompressionRepository.items)
        broadcastState()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(text: String, progress: Int, max: Int) {
        val notification = buildRunningNotification(text, progress, max)
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(index: Int) {
        if (!CompressionRepository.running) return
        val item = CompressionRepository.items.getOrNull(index) ?: return
        val selectedIndices = CompressionRepository.items.indices.filter { CompressionRepository.items[it].selected }
        val currentNumber = (selectedIndices.indexOf(index) + 1).coerceAtLeast(1)
        val total = selectedIndices.size.coerceAtLeast(1)
        val text = when (item.status) {
            VideoStatus.COMPRESSING -> "$currentNumber/$total · ${item.displayName} · ${item.progress}%"
            VideoStatus.DONE -> "$currentNumber/$total · 已完成 ${item.displayName}"
            VideoStatus.FAILED -> "$currentNumber/$total · 失败，继续下一个"
            VideoStatus.CANCELLED -> "任务已取消"
            VideoStatus.READY -> "$currentNumber/$total · 等待压缩"
        }
        val progress = if (item.status == VideoStatus.COMPRESSING) item.progress else 0
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            buildRunningNotification(text, progress, 100),
        )
    }

    private fun buildRunningNotification(text: String, progress: Int, max: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_video)
            .setContentTitle("视频瘦身正在后台压缩")
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(max, progress.coerceIn(0, max.coerceAtLeast(1)), max == 0)
            .addAction(
                0,
                "取消",
                PendingIntent.getService(
                    this,
                    2,
                    Intent(this, CompressionService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun showFinishedNotification(done: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_video)
            .setContentTitle("批量压缩完成")
            .setContentText("已完成 $done 个视频，打开 App 可批量删除原视频")
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(FINISHED_NOTIFICATION_ID, notification)
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "视频压缩任务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示后台批量视频压缩进度"
                },
            )
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:compression").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits mediaProcessing foreground-service time in a rolling 24h window.
        // Stop cleanly during the grace period instead of letting the system crash the app.
        cancelAndStop()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.videoshrink.action.START"
        const val ACTION_CANCEL = "com.example.videoshrink.action.CANCEL"
        const val ACTION_STATE_CHANGED = "com.example.videoshrink.action.STATE_CHANGED"
        const val EXTRA_SIDE = "target_side"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_QUALITY = "quality"

        private const val CHANNEL_ID = "video_compression"
        private const val NOTIFICATION_ID = 4101
        private const val FINISHED_NOTIFICATION_ID = 4102
    }
}
