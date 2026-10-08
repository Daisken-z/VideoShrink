package com.example.videoshrink

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
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
        return SafeMp4Muxer(mp4Muxer)
    }

    override fun getSupportedSampleMimeTypes(trackType: @C.TrackType Int): ImmutableList<String> {
        return delegate.getSupportedSampleMimeTypes(trackType)
    }

    override fun supportsWritingNegativeTimestampsInEditList(): Boolean = false

    /**
     * Mirrors the important behavior of Media3's InAppMp4Muxer while writing to MediaStore:
     * 1) preserve the output video's rotation metadata;
     * 2) ignore vendor metadata that Mp4Muxer doesn't support instead of aborting export.
     */
    private class SafeMp4Muxer(
        private val delegate: Muxer,
    ) : Muxer {
        override fun addTrack(format: Format): Int {
            val trackId = delegate.addTrack(format)
            if (MimeTypes.isVideo(format.sampleMimeType)) {
                delegate.addMetadataEntry(Mp4OrientationData(format.rotationDegrees))
            }
            return trackId
        }

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
