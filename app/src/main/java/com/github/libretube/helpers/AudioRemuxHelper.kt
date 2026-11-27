package com.github.libretube.helpers

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.github.libretube.extensions.TAG
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.nameWithoutExtension

/**
 * Converts downloaded audio files into the container that matches their codec, without
 * re-encoding the audio.
 */
object AudioRemuxHelper {
    private const val WEBM_EXTENSION = "webm"
    private const val OPUS_EXTENSION = "opus"
    private const val DEFAULT_MAX_SAMPLE_SIZE = 256 * 1024

    /**
     * Repackages a WebM file containing an Opus audio track into an Ogg Opus (.opus) file.
     *
     * The original file is deleted if the conversion succeeds. If the file isn't a WebM file
     * containing Opus audio, the conversion fails, or the Android version doesn't support
     * writing Ogg files (Android 10+ is required), the original file is kept.
     *
     * @return the path of the converted file, or null if the original file has been kept
     */
    fun remuxWebmOpusToOgg(input: Path): Path? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!input.extension.equals(WEBM_EXTENSION, ignoreCase = true)) return null

        val output = input.resolveSibling("${input.nameWithoutExtension}.$OPUS_EXTENSION")
        return try {
            if (remuxToOgg(input, output)) {
                input.deleteIfExists()
                output
            } else {
                output.deleteIfExists()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG(), "Failed to convert $input to Ogg Opus", e)
            runCatching { output.deleteIfExists() }
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun remuxToOgg(input: Path, output: Path): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.toString())

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ==
                    MediaFormat.MIMETYPE_AUDIO_OPUS
            } ?: return false
            val trackFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            muxer = MediaMuxer(output.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val muxerTrackIndex = muxer.addTrack(trackFormat)
            muxer.start()

            val maxSampleSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                DEFAULT_MAX_SAMPLE_SIZE
            }
            val buffer = ByteBuffer.allocate(maxSampleSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var sampleCount = 0
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                bufferInfo.set(0, sampleSize, extractor.sampleTime, flags)
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                sampleCount++

                extractor.advance()
            }

            muxer.stop()
            return sampleCount > 0 && output.exists() && output.fileSize() > 0
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
