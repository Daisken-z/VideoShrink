package com.example.videoshrink

import android.net.Uri

enum class VideoStatus {
    READY,
    COMPRESSING,
    DONE,
    FAILED,
    CANCELLED
}

data class VideoItem(
    val uri: Uri,
    val displayName: String,
    val originalSize: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    var selected: Boolean = true,
    var status: VideoStatus = VideoStatus.READY,
    var progress: Int = 0,
    var outputUri: Uri? = null,
    var outputSize: Long = 0L,
    var error: String? = null,
    var originalDeleted: Boolean = false,
)
