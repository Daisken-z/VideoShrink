package com.example.videoshrink

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerUtil
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.transformer.DefaultMuxer
import com.google.common.collect.ImmutableList
import java.io.FileOutputStream
import java.nio.ByteBuffer

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
        val mp4Muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(outputStream)).build()
        return MetadataFilteringMuxer(mp4Muxer)
    }

    override fun getSupportedSampleMimeTypes(trackType: @C.TrackType Int): ImmutableList<String> {
        return delegate.getSupportedSampleMimeTypes(trackType)
    }

    override fun supportsWritingNegativeTimestampsInEditList(): Boolean = false

    /**
     * Mirrors Media3's InAppMp4Muxer behavior: only forward metadata that Mp4Muxer supports.
     * Phone vendors may add custom MP4 metadata entries. Passing those directly to Mp4Muxer
     * throws IllegalArgumentException("Unsupported metadata"), which would otherwise abort export.
     */
    private class MetadataFilteringMuxer(
        private val delegate: Muxer,
    ) : Muxer {
        override fun addTrack(format: Format): Int = delegate.addTrack(format)

        override fun writeSampleData(
            trackId: Int,
            byteBuffer: ByteBuffer,
            bufferInfo: BufferInfo,
        ) {
            delegate.writeSampleData(trackId, byteBuffer, bufferInfo)
        }

        override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
            if (MuxerUtil.isMetadataSupported(metadataEntry)) {
                delegate.addMetadataEntry(metadataEntry)
            }
        }

        override fun close() {
            delegate.close()
        }
    }
}
