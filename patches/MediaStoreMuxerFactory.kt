package com.example.videoshrink

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.transformer.DefaultMuxer
import com.google.common.collect.ImmutableList
import java.io.FileOutputStream

/** Writes Media3 output directly into a pre-opened MediaStore file descriptor. */
@OptIn(UnstableApi::class)
class MediaStoreMuxerFactory(
    private val outputStream: FileOutputStream,
) : Muxer.Factory {
    private val delegate = DefaultMuxer.Factory()
    private var consumed = false

    override fun create(path: String): Muxer {
        check(!consumed) { "Muxer factory can only create one muxer" }
        consumed = true
        return Mp4Muxer.Builder(SeekableMuxerOutput.of(outputStream)).build()
    }

    override fun getSupportedSampleMimeTypes(trackType: @C.TrackType Int): ImmutableList<String> {
        return delegate.getSupportedSampleMimeTypes(trackType)
    }

    override fun supportsWritingNegativeTimestampsInEditList(): Boolean = false
}
